import { contrastRatio, normalizeHex, readableOn, withAlpha } from '../../lib/color';

// Цвета приложения. Встроенные темы и своя тема устроены одинаково: палитра ставится переменными CSS
// на .App (themeVariables), стили берут цвета только из этих переменных.

export const THEME_FIELDS = [
  { key: 'bg',         label: 'Фон' },
  { key: 'card',       label: 'Карточки' },
  { key: 'text',       label: 'Текст' },
  { key: 'hint',       label: 'Подписи' },
  { key: 'button',     label: 'Кнопки' },
  { key: 'buttonText', label: 'Текст кнопок' },
  { key: 'ok',         label: 'Успех' },
  { key: 'warning',    label: 'Предупреждение' },
  { key: 'error',      label: 'Ошибка' },
];

export const PRESETS = {
  light: {
    bg: '#eef3f2', card: '#ffffff', text: '#16302e', hint: '#55706d', button: '#1f7c79', buttonText: '#ffffff',
    ok: '#2e7d32', warning: '#9a5400', error: '#c62828',
  },
  dark: {
    bg: '#131c1b', card: '#1d2928', text: '#e2ebe9', hint: '#97ada9', button: '#5cc4a4', buttonText: '#0b1d1a',
    ok: '#72d17f', warning: '#f0b14f', error: '#ff8080',
  },
  contrastLight: {
    bg: '#ffffff', card: '#ffffff', text: '#000000', hint: '#1d3557', button: '#004f9e', buttonText: '#ffffff',
    ok: '#1b5e20', warning: '#7a4100', error: '#b00020',
  },
  contrastDark: {
    bg: '#000000', card: '#000000', text: '#ffffff', hint: '#ffd27f', button: '#ffb000', buttonText: '#000000',
    ok: '#7cfc7c', warning: '#ffcc66', error: '#ff8a8a',
  },
};

export const PRESET_TITLES = {
  light: 'Светлая', dark: 'Тёмная', contrastLight: 'Контрастная', contrastDark: 'Контрастная тёмная',
};

export const presetFor = (dark, contrast) => {
  if (contrast) return PRESETS[dark ? 'contrastDark' : 'contrastLight'];
  return PRESETS[dark ? 'dark' : 'light'];
};

// Сохранённые цвета: испорченное или пропущенное поле — из светлой основы.
export const sanitizeColors = (raw) =>
  Object.fromEntries(THEME_FIELDS.map(({ key }) => [key, normalizeHex(raw?.[key]) || PRESETS.light[key]]));

export function themeVariables(c) {
  return {
    '--bg': c.bg,
    '--surface': c.card,
    '--field': withAlpha(c.text, 0.05),
    '--text': c.text,
    '--hint': c.hint,
    '--accent': c.button,
    '--on-accent': c.buttonText,
    '--accent-tint': withAlpha(c.button, 0.12),
    '--line': withAlpha(c.text, 0.14),
    '--ok': c.ok,
    '--warning': c.warning,
    '--error': c.error,
    '--ok-tint': withAlpha(c.ok, 0.12),
    '--warning-tint': withAlpha(c.warning, 0.14),
    '--error-tint': withAlpha(c.error, 0.12),
    '--on-ok': readableOn(c.ok),
    '--on-warning': readableOn(c.warning),
    '--on-error': readableOn(c.error),
    // Раздел темы, пока она плохо читается: чёрный или белый — чтобы её было чем исправить (settings.css, .theme-safe).
    '--safe-text': readableOn(c.card),
    '--safe-field-text': readableOn(c.bg),
  };
}

// Что будет плохо читаться: обычному тексту нужен контраст от 4,5 : 1, крупному, жирному и значкам — от 3 : 1.
const CHECKS = [
  ['text', 'bg', 4.5, 'Текст на фоне'],
  ['text', 'card', 4.5, 'Текст на карточках'],
  ['hint', 'card', 3, 'Подписи на карточках'],
  ['button', 'bg', 3, 'Кнопки на фоне'],
  ['buttonText', 'button', 3, 'Текст на кнопках'],
];

export const readabilityIssues = (c) => CHECKS
  .map(([fg, bg, min, what]) => ({ what, min, ratio: contrastRatio(c[fg], c[bg]) }))
  .filter((issue) => issue.ratio < issue.min);
