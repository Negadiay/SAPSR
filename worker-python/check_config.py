"""Configurable formatting rules for the SAPSR PDF analyzer."""

# Page geometry is measured in PDF points for A4 pages.
PAGE_WIDTH = 595.28
PAGE_HEIGHT = 841.89

MARGIN_LEFT = 85.0
MARGIN_RIGHT = 553.0
MARGIN_TOP = 57.0
MARGIN_BOTTOM = 765.0
MARGIN_TOLERANCE = 10.0

# Main text font size is accepted as a range to avoid false negatives caused by
# PDF exporters rounding glyph metrics differently.
FONT_SIZE_MIN = 13.0
FONT_SIZE_MAX = 15.0

# Match the expected PDF font family. PDF exporters may add subset prefixes.
ALLOWED_FONT_FAMILIES = (
    "VOAZZS+SFRM1440",
)
FONT_FAMILY_VIOLATION_THRESHOLD = 0.30

# Page numbers should be in the bottom-right area on pages 3+.
PAGE_NUM_X_MIN = 460.0
PAGE_NUM_Y_MIN = 750.0
PAGE_NUMBER_MIN_COVERAGE = 0.60
PAGE_NUMBER_MIN_CHECKABLE_PAGES = 2

FONT_SIZE_MAJOR_THRESHOLD_PCT = 30.0

BSUIR_SPECIALITIES_URL = "https://iis.bsuir.by/api/v1/specialities"

MIN_REFERENCES = 10
CRITICAL_REFERENCES_THRESHOLD = 5

REQUIRED_SECTIONS = {
    "title_page": {"patterns": [r"министерство", r"белорусский", r"кафедра"], "name": "Титульный лист"},
    "toc": {"patterns": [r"содержание", r"оглавление"], "name": "Содержание"},
    "intro": {"patterns": [r"введение"], "name": "Введение"},
    "conclusion": {"patterns": [r"заключение"], "name": "Заключение"},
    "references": {
        "patterns": [r"список используемых источников"],
        "name": "Список используемых источников",
    },
}
