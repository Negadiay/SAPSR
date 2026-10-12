import { useState, useEffect } from 'react';

// Тема, размер шрифта и контраст; хранятся в localStorage устройства.
export function useSettings() {
  const [theme, setTheme]       = useState(() => localStorage.getItem('sapsr_theme') === 'dark' ? 'dark' : 'light');
  const [fontSize, setFontSize] = useState(() => localStorage.getItem('sapsr_fontsize') || 'normal');
  const [contrast, setContrast] = useState(() => localStorage.getItem('sapsr_contrast') === '1');

  useEffect(() => { localStorage.setItem('sapsr_theme', theme); }, [theme]);
  useEffect(() => { localStorage.setItem('sapsr_fontsize', fontSize); }, [fontSize]);
  useEffect(() => { localStorage.setItem('sapsr_contrast', contrast ? '1' : '0'); }, [contrast]);

  const toggleTheme = () => setTheme(t => t === 'dark' ? 'light' : 'dark');
  const toggleContrast = () => setContrast(c => !c);

  return { theme, fontSize, contrast, toggleTheme, toggleContrast, setFontSize };
}
