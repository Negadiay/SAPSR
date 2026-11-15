"""PDF с растровым шрифтом Type 3, как у pdfTeX без пакета cm-super (решение 15, задача Б1).

reportlab таких шрифтов не делает, поэтому файл пишется напрямую: один шрифт Type 3 без
BaseFont и ToUnicode, глифы названы <prefix><код T2A> и нарисованы прямоугольниками.
С prefix="a" получается то же, что вставляет pdfTeX (текст восстанавливается по таблице T2A);
с другим префиксом текст восстановить нельзя — образец нечитаемого текстового слоя.

Класс повторяет ту часть reportlab.pdfgen.canvas.Canvas, которой пользуется Writer из generate.py.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "worker-python"))

from sapsr.document.t2a import T2A

# Ширина глифа в тысячных долях кегля: близко к средней ширине буквы Times New Roman.
GLYPH_WIDTH = 450
_ENCODE = {char: code for code, char in T2A.items() if len(char) == 1}


def string_width(text: str, size: float) -> float:
    return len(text) * GLYPH_WIDTH / 1000 * size


class Type3Canvas:
    def __init__(self, path: str, pagesize: tuple[float, float], glyph_prefix: str = "a"):
        self.path = path
        self.width, self.height = pagesize
        self.prefix = glyph_prefix
        self.size = 14.0
        self.pages: list[list[str]] = []
        self.current: list[str] = []
        self.used: set[int] = set()

    # ── часть интерфейса reportlab ──────────────────────────────────────────

    def setTitle(self, title: str) -> None:
        pass

    def setFont(self, name: str, size: float) -> None:
        self.size = float(size)

    def drawString(self, x: float, y: float, text: str) -> None:
        try:
            codes = bytes(_ENCODE[ch] for ch in text)
        except KeyError as exc:
            raise ValueError(f"символа {exc.args[0]!r} нет в таблице T2A: {text!r}") from None
        self.used.update(codes)
        self.current.append(f"BT /F1 {self.size:g} Tf 1 0 0 1 {x:.2f} {y:.2f} Tm <{codes.hex()}> Tj ET")

    def drawCentredString(self, x: float, y: float, text: str) -> None:
        self.drawString(x - string_width(text, self.size) / 2, y, text)

    def drawRightString(self, x: float, y: float, text: str) -> None:
        self.drawString(x - string_width(text, self.size), y, text)

    def showPage(self) -> None:
        self.pages.append(self.current)
        self.current = []

    def save(self) -> None:
        objects: list[bytes] = []

        def add(body: str | bytes) -> int:
            objects.append(body.encode("latin1") if isinstance(body, str) else body)
            return len(objects)

        def stream(data: str) -> str:
            return f"<< /Length {len(data)} >>\nstream\n{data}\nendstream"

        codes = sorted(self.used)
        first, last = codes[0], codes[-1]
        glyph = add(stream(f"{GLYPH_WIDTH} 0 0 0 {GLYPH_WIDTH - 60} 680 d1 30 0 {GLYPH_WIDTH - 90} 680 re f"))
        space = add(stream(f"{GLYPH_WIDTH} 0 0 0 0 0 d1"))
        procs = " ".join(f"/{self.prefix}{c} {space if c == 0x20 else glyph} 0 R" for c in codes)
        differences = " ".join(f"{c} /{self.prefix}{c}" for c in codes)
        widths = " ".join(str(GLYPH_WIDTH if c in self.used else 0) for c in range(first, last + 1))
        font = add(f"<< /Type /Font /Subtype /Type3 /FontBBox [0 0 {GLYPH_WIDTH} 700] "
                   f"/FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << {procs} >> "
                   f"/Encoding << /Type /Encoding /Differences [{differences}] >> "
                   f"/FirstChar {first} /LastChar {last} /Widths [{widths}] >>")
        pages_id = len(objects) + 2 * len(self.pages) + 1
        kids = []
        for content in self.pages:
            content_id = add(stream("\n".join(content)))
            kids.append(add(f"<< /Type /Page /Parent {pages_id} 0 R /MediaBox [0 0 {self.width:.2f} {self.height:.2f}] "
                            f"/Resources << /Font << /F1 {font} 0 R >> >> /Contents {content_id} 0 R >>"))
        assert add(f"<< /Type /Pages /Count {len(kids)} /Kids [{' '.join(f'{k} 0 R' for k in kids)}] >>") == pages_id
        catalog = add(f"<< /Type /Catalog /Pages {pages_id} 0 R >>")

        out = bytearray(b"%PDF-1.4\n")
        offsets = []
        for number, body in enumerate(objects, start=1):
            offsets.append(len(out))
            out += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
        xref = len(out)
        out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
        out += "".join(f"{offset:010d} 00000 n \n" for offset in offsets).encode()
        out += f"trailer\n<< /Size {len(objects) + 1} /Root {catalog} 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
        Path(self.path).write_bytes(bytes(out))
