import { contrastRatio, normalizeHex, readableOn, withAlpha } from '../../lib/color';

// Своя тема (настройки → «Своя тема»): пользователь задаёт цвета сам, кругом или кодом HEX.
// Цвета ставятся переменными CSS на .App вместо ночного и контрастного режимов. Переменные --app-* без своей темы
// не заданы — стили берут запасное значение, то есть цвета встроенных тем.

export const THEME_FIELDS = [
  { key: 'bg',         label: 'Фон' },
  { key: 'card',       label: 'Карточки' },
  { key: 'text',       label: 'Текст' },
  { key: 'hint',       label: 'Подписи' },
  { key: 'button',     label: 'Кнопки' },
  { key: 'buttonText', label: 'Текст кнопок' },
  { key: 'nav',        label: 'Нижняя панель' },
  { key: 'ok',         label: 'Успех' },
  { key: 'warning',    label: 'Предупреждение' },
  { key: 'error',      label: 'Ошибка' },
];

// Основы — цвета встроенных тем (styles/base.css, styles/themes.css).
export const PRESETS = {
  light: {
    bg: '#f0f9f5', card: '#dff0e9', text: '#1c3a38', hint: '#6a8a88', button: '#2e8d8a', buttonText: '#ffffff',
    nav: '#1c3a38', ok: '#388e3c', warning: '#e68a00', error: '#d32f2f',
  },
  dark: {
    bg: '#212d2f', card: '#2a3a3c', text: '#d1dbd8', hint: '#8aa09e', button: '#63c9a0', buttonText: '#0f1f1d',
    nav: '#0f1618', ok: '#63c9a0', warning: '#ffb84d', error: '#ff6b6b',
  },
  contrastLight: {
    bg: '#eaf7ff', card: '#d8f2ff', text: '#03233f', hint: '#275f86', button: '#006bb6', buttonText: '#ffffff',
    nav: '#004678', ok: '#2e7d32', warning: '#a85400', error: '#c62828',
  },
  contrastDark: {
    bg: '#000000', card: '#141414', text: '#ffffff', hint: '#ccaa77', button: '#ff8c00', buttonText: '#000000',
    nav: '#141414', ok: '#66bb6a', warning: '#ffb84d', error: '#ff6b6b',
  },
};

export const PRESET_TITLES = {
  light: 'Светлая', dark: 'Тёмная', contrastLight: 'Контрастная', contrastDark: 'Контрастная тёмная',
};

// Основа под то, что сейчас на экране: своя тема начинается с привычных цветов.
export const presetFor = (theme, contrast) => {
  const dark = theme === 'dark';
  if (contrast) return PRESETS[dark ? 'contrastDark' : 'contrastLight'];
  return PRESETS[dark ? 'dark' : 'light'];
};

// Сохранённые цвета: испорченное или пропущенное поле — из светлой основы.
export const sanitizeColors = (raw) =>
  Object.fromEntries(THEME_FIELDS.map(({ key }) => [key, normalizeHex(raw?.[key]) || PRESETS.light[key]]));

// Стрелка выпадающего списка цвета кнопок — та же, что в styles: features/student/upload.css.
const selectArrow = (color) => `url("data:image/svg+xml,${encodeURIComponent(
  `<svg xmlns='http://www.w3.org/2000/svg' width='12' height='8' viewBox='0 0 12 8'><path d='M1 1l5 5 5-5' stroke='${color}' fill='none' stroke-width='2'/></svg>`,
)}")`;

export function themeVariables(c) {
  return {
    '--tg-theme-bg-color': c.bg,
    '--tg-theme-secondary-bg-color': c.card,
    '--tg-theme-text-color': c.text,
    '--tg-theme-hint-color': c.hint,
    '--tg-theme-button-color': c.button,
    '--tg-theme-button-text-color': c.buttonText,
    '--app-on-button': c.buttonText,
    '--app-accent': c.button,
    '--app-nav-bg': c.nav,
    '--app-on-nav': withAlpha(readableOn(c.nav), 0.8),
    '--app-ok': c.ok,
    '--app-warning': c.warning,
    '--app-error': c.error,
    '--app-on-ok': readableOn(c.ok),
    '--app-on-warning': readableOn(c.warning),
    '--app-on-error': readableOn(c.error),
    '--app-ok-tint': withAlpha(c.ok, 0.14),
    '--app-warning-tint': withAlpha(c.warning, 0.14),
    '--app-line': withAlpha(c.button, 0.22),
    '--app-tint': withAlpha(c.button, 0.1),
    '--app-muted': withAlpha(c.hint, 0.5),
    '--app-popup-bg': c.bg,
    '--app-popup-text': c.text,
    '--app-popup-hint': c.hint,
    '--app-select-arrow': selectArrow(c.button),
    // Раздел темы, пока она плохо читается: чёрный или белый — чтобы её было чем исправить (settings.css, .theme-safe).
    '--app-safe-text': readableOn(c.card),
    '--app-safe-field-text': readableOn(c.bg),
  };
}

// Что будет плохо читаться: обычному тексту нужен контраст от 4,5 : 1, крупному, жирному и значкам — от 3 : 1.
const CHECKS = [
  ['text', 'bg', 4.5, 'Текст на фоне'],
  ['text', 'card', 4.5, 'Текст на карточках'],
  ['hint', 'card', 3, 'Подписи на карточках'],
  ['button', 'bg', 3, 'Кнопки и заголовки на фоне'],
  ['buttonText', 'button', 3, 'Текст на кнопках'],
];

export const readabilityIssues = (c) => CHECKS
  .map(([fg, bg, min, what]) => ({ what, min, ratio: contrastRatio(c[fg], c[bg]) }))
  .filter((issue) => issue.ratio < issue.min);
