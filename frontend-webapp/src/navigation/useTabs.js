import { useState, useEffect, useRef } from 'react';

// Активная вкладка, направление анимации и переключение свайпом.
export function useTabs({ swipeEnabled, tabCount, swipeBlockedRef }) {
  const [activeTab, setActiveTab] = useState(0);
  const [direction, setDirection] = useState(0);

  // Свайп-навигация — исключаем слайдеры и активный туториал
  const swipeStartX = useRef(0);
  const swipeStartY = useRef(0);
  const swipeActive = useRef(false);

  useEffect(() => {
    if (!swipeEnabled) return;
    const onStart = (e) => {
      // Не свайпать во время туториала
      if (swipeBlockedRef.current) { swipeActive.current = false; return; }
      // Не свайпать при взаимодействии со слайдером
      if (e.target.tagName === 'INPUT' && e.target.type === 'range') {
        swipeActive.current = false;
        return;
      }
      swipeStartX.current = e.touches[0].clientX;
      swipeStartY.current = e.touches[0].clientY;
      swipeActive.current = true;
    };
    const onEnd = (e) => {
      if (!swipeActive.current) return;
      swipeActive.current = false;
      const dx = e.changedTouches[0].clientX - swipeStartX.current;
      const dy = e.changedTouches[0].clientY - swipeStartY.current;
      if (Math.abs(dx) < 50 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
      setDirection(dx < 0 ? 1 : -1);
      setActiveTab(prev => dx < 0 ? (prev + 1) % tabCount : (prev - 1 + tabCount) % tabCount);
    };
    document.addEventListener('touchstart', onStart, { passive: true });
    document.addEventListener('touchend', onEnd, { passive: true });
    return () => {
      document.removeEventListener('touchstart', onStart);
      document.removeEventListener('touchend', onEnd);
    };
  }, [swipeEnabled, tabCount, swipeBlockedRef]);

  const showTab = (index, animationDirection) => {
    setDirection(animationDirection);
    setActiveTab(index);
  };

  const goToTab = (index) => showTab(index, index > activeTab ? 1 : -1);

  return { activeTab, direction, showTab, goToTab };
}
