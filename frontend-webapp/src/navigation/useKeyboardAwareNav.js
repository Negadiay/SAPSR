import { useState, useEffect, useRef } from 'react';
import { getPlatform } from '../telegram/webApp';

// На мобильных клавиатура перекрывает нижнюю панель, а активное поле прокручивается выше.
export function useKeyboardAwareNav() {
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const navRef = useRef(null);

  useEffect(() => {
    const vp = window.visualViewport;
    const isEditable = (target) => target instanceof HTMLElement
      && (target.matches('input:not([type="range"]), textarea, select') || target.isContentEditable);
    const isMobileInput = () => /android|ios|iphone|ipad|mobile/i.test(getPlatform())
      || window.matchMedia?.('(pointer: coarse)').matches;
    const onFocusIn = (e) => {
      if (!isMobileInput() || !isEditable(e.target)) return;
      setKeyboardOpen(true);
      window.setTimeout(() => {
        e.target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
      }, 250);
    };
    const onFocusOut = () => {
      window.setTimeout(() => {
        if (!isEditable(document.activeElement)) setKeyboardOpen(false);
      }, 0);
    };
    const reposition = () => {
      const el = navRef.current;
      if (!el) return;
      const kbHeight = Math.max(0, window.innerHeight - vp.height - vp.offsetTop);
      el.style.transform = kbHeight > 60 ? `translateY(${kbHeight}px)` : '';
    };
    document.addEventListener('focusin', onFocusIn);
    document.addEventListener('focusout', onFocusOut);
    vp?.addEventListener('resize', reposition);
    vp?.addEventListener('scroll', reposition);
    return () => {
      document.removeEventListener('focusin', onFocusIn);
      document.removeEventListener('focusout', onFocusOut);
      vp?.removeEventListener('resize', reposition);
      vp?.removeEventListener('scroll', reposition);
    };
  }, []);

  return { keyboardOpen, navRef };
}
