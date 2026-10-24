// Цвета своей темы: HEX, круг оттенков (HSV) и читаемость по WCAG 2.

// «#2E8D8A», «2e8d8a», «#abc» → «#2e8d8a» / «#aabbcc»; иное — null.
export function normalizeHex(value) {
  const v = String(value ?? '').trim().replace(/^#/, '').toLowerCase();
  if (/^[0-9a-f]{3}$/.test(v)) return '#' + [...v].map((c) => c + c).join('');
  if (/^[0-9a-f]{6}$/.test(v)) return '#' + v;
  return null;
}

export function hexToRgb(hex) {
  const n = parseInt(normalizeHex(hex).slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

export const rgbToHex = (rgb) => '#' + rgb.map((c) => Math.round(c).toString(16).padStart(2, '0')).join('');

// h — оттенок 0–360° (угол на круге), s — насыщенность 0–1 (от центра к краю), v — яркость 0–1.
export function hexToHsv(hex) {
  const [r, g, b] = hexToRgb(hex).map((c) => c / 255);
  const max = Math.max(r, g, b);
  const d = max - Math.min(r, g, b);
  let h = 0;
  if (d) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
  }
  return { h: (h * 60 + 360) % 360, s: max ? d / max : 0, v: max };
}

export function hsvToHex({ h, s, v }) {
  const f = (n) => {
    const k = (n + h / 60) % 6;
    return (v - v * s * Math.max(0, Math.min(k, 4 - k, 1))) * 255;
  };
  return rgbToHex([f(5), f(3), f(1)]);
}

const channel = (c) => {
  const x = c / 255;
  return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
};

export function luminance(hex) {
  const [r, g, b] = hexToRgb(hex).map(channel);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

// Контраст двух цветов, 1–21. Обычному тексту нужно от 4,5, крупному и значкам — от 3.
export function contrastRatio(a, b) {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}

// Белый или почти чёрный — что лучше читается на этом цвете.
export const readableOn = (hex) => (contrastRatio(hex, '#ffffff') >= contrastRatio(hex, '#111111') ? '#ffffff' : '#111111');

export function withAlpha(hex, alpha) {
  const [r, g, b] = hexToRgb(hex);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
