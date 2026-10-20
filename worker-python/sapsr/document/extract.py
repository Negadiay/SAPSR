"""Шаг 1 цепочки: извлечение символов, строк и координат из PDF (pdfplumber).

Раньше это было перемешано с проверками в одном цикле analyze_pdf; теперь на
выходе — RawDocument, по которому разметчик строит модель документа, а правила
оформления меряют поля, шрифты и интервалы.

Скрытый текст (белый, микроскопический, за краем страницы) отделяется здесь же:
в строки, текст страниц и вход языковой модели он не попадает (план, З9).
"""

from __future__ import annotations

import hashlib
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple, Optional

import pdfplumber
from pdfminer.pdfdocument import PDFEncryptionError, PDFPasswordIncorrect
from pdfplumber.utils.exceptions import PdfminerException

from sapsr.document.model import HiddenFragment
from sapsr.document.text import clean_line, is_bold_font, is_italic_font
from sapsr.errors import CheckFailure
from sapsr.kb.schema import LayoutParams


class Char(NamedTuple):
    text: str
    font: str
    size: float
    x0: float
    x1: float
    top: float
    bottom: float


@dataclass
class RawLine:
    page: int
    text: str
    x0: float
    top: float
    x1: float
    bottom: float
    chars: list[Char]
    font: str
    size: float
    bold: bool
    italic: bool

    @property
    def height(self) -> float:
        return max(self.bottom - self.top, 0.0)


@dataclass
class RawPage:
    number: int
    width: float
    height: float
    # Видимые непробельные символы страницы.
    chars: list[Char]
    lines: list[RawLine]
    # Текст страницы в том виде, в каком его раньше получал анализатор (page.extract_text()).
    text: str
    hidden: list[HiddenFragment] = field(default_factory=list)


@dataclass
class RawDocument:
    path: str
    sha256: str
    pages: list[RawPage]

    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def full_text(self) -> str:
        return "\n".join(p.text for p in self.pages)

    @property
    def first_page_text(self) -> str:
        return self.pages[0].text if self.pages else ""

    @property
    def has_text(self) -> bool:
        return any(p.text.strip() for p in self.pages)


def file_sha256(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


# ── скрытый текст ───────────────────────────────────────────────────────────

def _color_is_white(color, threshold: float) -> bool:
    if color is None or isinstance(color, str):
        return False
    try:
        values = [float(v) for v in (color if isinstance(color, (list, tuple)) else (color,))]
    except (TypeError, ValueError):
        return False
    if len(values) in (1, 3):
        return all(v >= threshold for v in values)
    if len(values) == 4:  # CMYK: белый — все компоненты около нуля
        return all(v <= 1 - threshold for v in values)
    return False


def hidden_reason(obj: dict, width: float, height: float, params: LayoutParams) -> Optional[str]:
    """Почему символ невидим читателю, или None."""
    text = obj.get("text", "")
    if not text.strip():
        return None
    hp = params.hidden_text
    if _color_is_white(obj.get("non_stroking_color"), hp.white_threshold):
        return "white"
    if float(obj.get("size", 0) or 0) < hp.min_size_pt:
        return "tiny"
    margin = hp.page_overflow_pt
    if (obj.get("x1", 0) < -margin or obj.get("x0", 0) > width + margin
            or obj.get("bottom", 0) < -margin or obj.get("top", 0) > height + margin):
        return "offpage"
    return None


def _group_hidden(page_no: int, items: list[tuple[str, dict]]) -> list[HiddenFragment]:
    fragments: list[HiddenFragment] = []
    by_reason: dict[str, list[dict]] = {}
    for reason, obj in items:
        by_reason.setdefault(reason, []).append(obj)
    for reason, objs in sorted(by_reason.items()):
        objs.sort(key=lambda o: (round(o.get("top", 0)), o.get("x0", 0)))
        text = "".join(o.get("text", "") for o in objs)
        bbox = (
            round(min(o.get("x0", 0) for o in objs), 1),
            round(min(o.get("top", 0) for o in objs), 1),
            round(max(o.get("x1", 0) for o in objs), 1),
            round(max(o.get("bottom", 0) for o in objs), 1),
        )
        fragments.append(HiddenFragment(page=page_no, reason=reason, text=text, bbox=bbox))
    return fragments


# ── строки ──────────────────────────────────────────────────────────────────

def _to_char(obj: dict) -> Char:
    return Char(
        text=obj.get("text", ""),
        font=obj.get("fontname") or "",
        size=float(obj.get("size", 0) or 0),
        x0=float(obj.get("x0", 0)),
        x1=float(obj.get("x1", 0)),
        top=float(obj.get("top", 0)),
        bottom=float(obj.get("bottom", 0)),
    )


def _make_line(page_no: int, raw: dict) -> Optional[RawLine]:
    chars = [_to_char(c) for c in raw.get("chars", []) if c.get("text", "").strip()]
    text = clean_line(raw.get("text", ""))
    if not chars or not text:
        return None
    font_counts = Counter(c.font for c in chars if c.font)
    size_counts = Counter(round(c.size, 1) for c in chars)
    font = font_counts.most_common(1)[0][0] if font_counts else ""
    bold_share = sum(1 for c in chars if is_bold_font(c.font)) / len(chars)
    italic_share = sum(1 for c in chars if is_italic_font(c.font)) / len(chars)
    return RawLine(
        page=page_no,
        text=text,
        x0=float(raw["x0"]),
        top=float(raw["top"]),
        x1=float(raw["x1"]),
        bottom=float(raw["bottom"]),
        chars=chars,
        font=font,
        size=size_counts.most_common(1)[0][0],
        bold=bold_share > 0.5,
        italic=italic_share > 0.5,
    )


# ── главный вход ────────────────────────────────────────────────────────────

def _open(path: str):
    try:
        return pdfplumber.open(path)
    except FileNotFoundError:
        raise CheckFailure("FILE_NOT_FOUND", "файл не найден") from None
    except (PDFPasswordIncorrect, PDFEncryptionError):
        raise CheckFailure("PDF_ENCRYPTED", "PDF защищён паролем") from None
    except PdfminerException as exc:
        inner = exc.args[0] if exc.args else None
        if isinstance(inner, (PDFPasswordIncorrect, PDFEncryptionError)):
            raise CheckFailure("PDF_ENCRYPTED", "PDF защищён паролем") from None
        raise CheckFailure("PDF_UNREADABLE", f"pdfminer: {type(inner).__name__ if inner else exc}") from exc
    except Exception as exc:  # повреждённый файл, не PDF
        raise CheckFailure("PDF_UNREADABLE", f"{type(exc).__name__}") from exc


def extract(path: str | Path, params: LayoutParams, max_pages: int,
            sha256: Optional[str] = None) -> RawDocument:
    """Разбирает PDF. Ошибки чтения превращаются в CheckFailure с кодом из протокола очередей."""
    path = str(path)
    if not Path(path).is_file():
        raise CheckFailure("FILE_NOT_FOUND", "файл не найден")
    digest = sha256 or file_sha256(path)

    pdf = _open(path)
    try:
        try:
            page_objs = pdf.pages
        except (PDFPasswordIncorrect, PDFEncryptionError):
            raise CheckFailure("PDF_ENCRYPTED", "PDF защищён паролем") from None
        except CheckFailure:
            raise
        except Exception as exc:
            raise CheckFailure("PDF_UNREADABLE", type(exc).__name__) from exc
        if len(page_objs) > max_pages:
            raise CheckFailure("TOO_LARGE", f"{len(page_objs)} страниц, предел {max_pages}",
                               max_pages=max_pages)

        pages: list[RawPage] = []
        y_tol = params.labeler.line_y_tolerance_pt
        for number, page in enumerate(page_objs, start=1):
            try:
                pages.append(_extract_page(number, page, params, y_tol))
            except CheckFailure:
                raise
            except (PDFPasswordIncorrect, PDFEncryptionError):
                raise CheckFailure("PDF_ENCRYPTED", "PDF защищён паролем") from None
            except Exception as exc:
                raise CheckFailure("PDF_UNREADABLE", f"страница {number}: {type(exc).__name__}") from exc
            finally:
                page.close()
        return RawDocument(path=path, sha256=digest, pages=pages)
    finally:
        pdf.close()


def _extract_page(number: int, page, params: LayoutParams, y_tol: float) -> RawPage:
    width, height = float(page.width), float(page.height)
    hidden_items: list[tuple[str, dict]] = []
    hidden_ids: set[int] = set()
    for obj in page.chars:
        reason = hidden_reason(obj, width, height, params)
        if reason:
            hidden_items.append((reason, obj))
            hidden_ids.add(id(obj))

    visible = page.filter(lambda o: id(o) not in hidden_ids) if hidden_ids else page
    text = visible.extract_text() or ""
    raw_lines = visible.extract_text_lines(return_chars=True, y_tolerance=y_tol)
    lines = [ln for ln in (_make_line(number, r) for r in raw_lines) if ln is not None]
    chars = [_to_char(c) for c in page.chars
             if c.get("text", "").strip() and id(c) not in hidden_ids]
    return RawPage(
        number=number, width=width, height=height, chars=chars, lines=lines, text=text,
        hidden=_group_hidden(number, hidden_items),
    )
