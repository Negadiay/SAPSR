import { useState, useEffect } from 'react';
import { PRESETS, presetFor, sanitizeColors } from './customTheme';
import { getColorScheme, onThemeChanged } from '../../telegram/webApp';

const MODE_KEY = 'sapsr_theme_mode';
const OLD_THEME_KEY = 'sapsr_theme';
const FONT_KEY = 'sapsr_fontsize';
const CONTRAST_KEY = 'sapsr_contrast';
const CUSTOM_KEY = 'sapsr_custom_theme';
export const FONT_SIZES = ['small', 'normal', 'large', 'xlarge'];

const read = (key) => {
  try { return localStorage.getItem(key); } catch { return null; }
};

const write = (key, value) => {
  try { localStorage.setItem(key, value); } catch { /* не запомнится — не страшно */ }
};

// Тема: auto — как в Telegram (или в системе), light, dark. Раньше выбора «как в Telegram» не было и
// светлая записывалась всем: прежняя «тёмная» — осознанный выбор, прежняя «светлая» — нет.
const readMode = () => {
  const mode = read(MODE_KEY);
  if (mode === 'auto' || mode === 'light' || mode === 'dark') return mode;
  return read(OLD_THEME_KEY) === 'dark' ? 'dark' : 'auto';
};

// Своя тема: включена ли и её цвета. Цвета остаются и после выключения — при включении вернутся.
function readCustom() {
  try {
    const saved = JSON.parse(read(CUSTOM_KEY));
    if (saved && typeof saved === 'object') {
      return { enabled: saved.enabled === true, colors: saved.colors ? sanitizeColors(saved.colors) : null };
    }
  } catch {
    // испорченная запись — как без неё
  }
  return { enabled: false, colors: null };
}

// Тема, контраст, своя тема и размер текста; хранятся на устройстве.
export function useSettings() {
  const [mode, setMode]         = useState(readMode);
  const [scheme, setScheme]     = useState(getColorScheme);
  const [fontSize, setFontSize] = useState(() => (FONT_SIZES.includes(read(FONT_KEY)) ? read(FONT_KEY) : 'normal'));
  const [contrast, setContrast] = useState(() => read(CONTRAST_KEY) === '1');
  const [custom, setCustom]     = useState(readCustom);

  useEffect(() => onThemeChanged(() => setScheme(getColorScheme())), []);
  useEffect(() => { write(MODE_KEY, mode); }, [mode]);
  useEffect(() => { write(FONT_KEY, fontSize); }, [fontSize]);
  useEffect(() => { write(CONTRAST_KEY, contrast ? '1' : '0'); }, [contrast]);
  useEffect(() => { write(CUSTOM_KEY, JSON.stringify(custom)); }, [custom]);

  const dark = mode === 'dark' || (mode === 'auto' && scheme === 'dark');
  const colors = custom.enabled && custom.colors ? custom.colors : presetFor(dark, contrast);

  const toggleContrast = () => setContrast((c) => !c);
  // Впервые включённая своя тема начинается с цветов, которые сейчас на экране.
  const toggleCustom = () => setCustom((c) => (c.enabled
    ? { ...c, enabled: false }
    : { enabled: true, colors: c.colors || { ...presetFor(dark, contrast) } }));
  const setCustomColor = (key, hex) => setCustom((c) => ({ ...c, colors: { ...c.colors, [key]: hex } }));
  const applyPreset = (id) => setCustom((c) => ({ ...c, colors: { ...PRESETS[id] } }));

  return {
    mode, setMode, dark, contrast, toggleContrast, custom, toggleCustom, setCustomColor, applyPreset,
    fontSize, setFontSize, colors,
  };
}
