import { useState, useEffect } from 'react';
import { PRESETS, presetFor, sanitizeColors } from './customTheme';

const CUSTOM_KEY = 'sapsr_custom_theme';

// Своя тема: включена ли и её цвета. Цвета остаются и после выключения — при включении вернутся.
function readCustom() {
  try {
    const saved = JSON.parse(localStorage.getItem(CUSTOM_KEY));
    if (saved && typeof saved === 'object') {
      return { enabled: saved.enabled === true, colors: saved.colors ? sanitizeColors(saved.colors) : null };
    }
  } catch {
    // испорченная запись — как без неё
  }
  return { enabled: false, colors: null };
}

// Тема, размер шрифта, контраст и своя тема; хранятся в localStorage устройства.
export function useSettings() {
  const [theme, setTheme]       = useState(() => localStorage.getItem('sapsr_theme') === 'dark' ? 'dark' : 'light');
  const [fontSize, setFontSize] = useState(() => localStorage.getItem('sapsr_fontsize') || 'normal');
  const [contrast, setContrast] = useState(() => localStorage.getItem('sapsr_contrast') === '1');
  const [custom, setCustom]     = useState(readCustom);

  useEffect(() => { localStorage.setItem('sapsr_theme', theme); }, [theme]);
  useEffect(() => { localStorage.setItem('sapsr_fontsize', fontSize); }, [fontSize]);
  useEffect(() => { localStorage.setItem('sapsr_contrast', contrast ? '1' : '0'); }, [contrast]);
  useEffect(() => { localStorage.setItem(CUSTOM_KEY, JSON.stringify(custom)); }, [custom]);

  const toggleTheme = () => setTheme(t => t === 'dark' ? 'light' : 'dark');
  const toggleContrast = () => setContrast(c => !c);
  // Впервые включённая своя тема начинается с цветов, которые сейчас на экране.
  const toggleCustom = () => setCustom(c => (c.enabled
    ? { ...c, enabled: false }
    : { enabled: true, colors: c.colors || { ...presetFor(theme, contrast) } }));
  const setCustomColor = (key, hex) => setCustom(c => ({ ...c, colors: { ...c.colors, [key]: hex } }));
  const applyPreset = (id) => setCustom(c => ({ ...c, colors: { ...PRESETS[id] } }));

  return {
    theme, fontSize, contrast, custom,
    toggleTheme, toggleContrast, toggleCustom, setCustomColor, applyPreset, setFontSize,
  };
}
