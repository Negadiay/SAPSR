// Даты — по Минску, независимо от часового пояса телефона: сервер присылает время по Минску без пояса,
// а время с поясом («…Z», «…+03:00») переводится в минское.
const MINSK = 'Europe/Minsk';
const WALL = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?/;
const ZONED = /(?:Z|[+-]\d{2}:?\d{2})$/i;

const minskParts = (date) => Object.fromEntries(new Intl.DateTimeFormat('ru-RU', {
  timeZone: MINSK, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
}).formatToParts(date).map(({ type, value }) => [type, value]));

const parts = (iso) => {
  if (!iso) return null;
  const text = String(iso);
  if (ZONED.test(text)) {
    const date = new Date(text);
    if (Number.isNaN(date.getTime())) return null;
    const p = minskParts(date);
    return { y: p.year, m: p.month, d: p.day, hh: p.hour, mm: p.minute };
  }
  const m = text.match(WALL);
  return m ? { y: m[1], m: m[2], d: m[3], hh: m[4], mm: m[5] } : null;
};

const minskYear = (now = new Date()) => minskParts(now).year;

// «25.10.2026, 10:02»; short — без года, если год текущий: «25.10, 10:02».
export const formatDate = (iso, { short = false, now } = {}) => {
  const p = parts(iso);
  if (!p) return '';
  const day = short && p.y === minskYear(now) ? `${p.d}.${p.m}` : `${p.d}.${p.m}.${p.y}`;
  return p.hh ? `${day}, ${p.hh}:${p.mm}` : day;
};

// Только день: «26.12.2026», short — «26.12» в текущем году.
export const formatDay = (iso, { short = false, now } = {}) => {
  const p = parts(iso);
  if (!p) return '';
  return short && p.y === minskYear(now) ? `${p.d}.${p.m}` : `${p.d}.${p.m}.${p.y}`;
};

export const formatCountdown = (seconds) => {
  const safeSeconds = Math.max(0, seconds);
  const minutes = Math.floor(safeSeconds / 60);
  const rest = String(safeSeconds % 60).padStart(2, '0');
  return `${minutes}:${rest}`;
};

// «1 замечание», «3 замечания», «5 замечаний».
const plural = (n, one, few, many) => {
  const mod100 = n % 100;
  const mod10 = n % 10;
  if (mod100 >= 11 && mod100 <= 14) return many;
  if (mod10 === 1) return one;
  if (mod10 >= 2 && mod10 <= 4) return few;
  return many;
};

export const count = (n, one, few, many) => `${n} ${plural(n, one, few, many)}`;

export const formatSize = (bytes) => (bytes >= 1024 * 1024
  ? `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} МБ`
  : `${Math.max(1, Math.round(bytes / 1024))} КБ`);
