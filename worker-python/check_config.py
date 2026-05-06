"""Configurable formatting rules for the SAPSR PDF analyzer."""

# Page geometry is measured in PDF points for A4 pages.
PAGE_WIDTH = 595.28
PAGE_HEIGHT = 841.89

MARGIN_LEFT = 85.0
MARGIN_RIGHT = 553.0
MARGIN_TOP = 57.0
MARGIN_BOTTOM = 765.0
MARGIN_TOLERANCE = 15.0

# Main text font size: range widened to reduce LaTeX false negatives.
# pdfLaTeX may report 13.28pt for 14pt text due to internal scaling.
FONT_SIZE_MIN = 12.0
FONT_SIZE_MAX = 16.0

# Allowed base font families (matched after stripping subset prefix before "+").
# LaTeX embeds fonts as subsets with a random 6-letter prefix, e.g. VOAZZS+SFRM1440.
ALLOWED_FONT_FAMILIES = (
    "sfrm",          # Computer Modern (pdfLaTeX default, BSUIR templates)
    "sfti",          # Computer Modern Italic
    "sfit",          # Computer Modern Italic alt
    "sfbx",          # Computer Modern Bold
    "cmr",           # Computer Modern Roman
    "cmtt",          # Computer Modern Typewriter
    "cmmi",          # Computer Modern Math Italic
    "lmroman",       # Latin Modern Roman (XeLaTeX)
    "lmtt",          # Latin Modern Typewriter
    "lmsans",        # Latin Modern Sans
    "timesnewroman", # Times New Roman (Word export)
    "times",         # Times (LaTeX times package)
    "nimburom",      # Nimbus Roman No.9 (common Linux PostScript equiv)
    "nimburo",       # Nimbus Roman variant
    "arial",         # Arial
    "helvetica",     # Helvetica
    "calibri",       # Calibri (Word 2007+)
    "georgia",       # Georgia
    "dejavuserif",   # DejaVu Serif
    "freserif",      # FreeSerif
    "ptserif",       # PT Serif (popular in Russian academic docs)
    "paratype",      # ParaType fonts
    "cmu",           # Computer Modern Unicode (XeLaTeX)
    "ptsans",        # PT Sans
)
# Only flag font family as a problem if more than 60% of pages use an unexpected font.
FONT_FAMILY_VIOLATION_THRESHOLD = 0.60

# Page numbers: accept both centered (LaTeX default) and right-aligned placement.
PAGE_NUM_X_MIN = 200.0
PAGE_NUM_Y_MIN = 740.0
PAGE_NUMBER_MIN_COVERAGE = 0.50
PAGE_NUMBER_MIN_CHECKABLE_PAGES = 3

FONT_SIZE_MAJOR_THRESHOLD_PCT = 30.0

BSUIR_SPECIALITIES_URL = "https://iis.bsuir.by/api/v1/specialities"
BSUIR_EMPLOYEES_ALL_URL = "https://iis.bsuir.by/api/v1/employees/all"

MIN_REFERENCES = 10
CRITICAL_REFERENCES_THRESHOLD = 5

REQUIRED_SECTIONS = {
    "title_page": {"patterns": [r"министерство", r"белорусский", r"кафедра"], "name": "Титульный лист"},
    "toc": {"patterns": [r"содержание", r"оглавление"], "name": "Содержание"},
    "intro": {"patterns": [r"введение"], "name": "Введение"},
    "conclusion": {"patterns": [r"заключение"], "name": "Заключение"},
    "references": {
        "patterns": [
            r"(?:с|c)писок\s+использованных\s+источников",
            r"(?:с|c)писок\s+используемых\s+источников",
            r"(?:с|c)писок\s+литературы",
        ],
        "name": "Список использованных источников",
    },
}
