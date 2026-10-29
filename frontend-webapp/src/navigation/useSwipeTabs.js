import { useEffect, useRef } from 'react';

// Свайп влево-вправо по экрану переключает его главные вкладки: «На проверке» ↔ «Проверено» у преподавателя,
// «Учёба» ↔ «Красная зона» у администратора. Влево — следующая вкладка, вправо — предыдущая, по кругу не ходит.
//
// Жест засчитывается, только если он явно горизонтальный и быстрый: вертикальная прокрутка списка его не
// вызовет. Не срабатывает на полях ввода (там выделяют текст), ползунках, цветовом круге, в окнах подтверждения
// и в рядах, которые сами листаются вбок.
const MIN_DX = 60;     // px — короче — случайное касание
const RATIO = 1.5;     // во столько раз горизонтальный сдвиг больше вертикального
const MAX_MS = 700;    // медленное движение — это перетаскивание или выделение, а не свайп

const scrollsSideways = (el) => {
  for (let node = el; node && node !== document.body; node = node.parentElement) {
    const overflow = getComputedStyle(node).overflowX;
    if ((overflow === 'auto' || overflow === 'scroll') && node.scrollWidth > node.clientWidth) return true;
  }
  return false;
};

const ignored = (target) => target instanceof Element
  && (Boolean(target.closest('input, textarea, select, [role="dialog"], [data-no-swipe]')) || scrollsSideways(target));

/**
 * Возвращает ref для элемента экрана, на котором ловится свайп. onChange(id) получает соседнюю вкладку.
 * Слушатели пассивные — прокрутку свайп не задерживает.
 */
export function useSwipeTabs({ ids, current, onChange, enabled = true }) {
  const ref = useRef(null);
  // Свежие значения без пересоздания слушателей на каждую отрисовку.
  const state = useRef({ ids, current, onChange });
  useEffect(() => { state.current = { ids, current, onChange }; });

  useEffect(() => {
    const el = ref.current;
    if (!el || !enabled) return undefined;
    let start = null;
    const onStart = (e) => {
      start = e.touches.length === 1 && !ignored(e.target)
        ? { x: e.touches[0].clientX, y: e.touches[0].clientY, at: Date.now() }
        : null;
    };
    const onEnd = (e) => {
      if (!start) return;
      const touch = e.changedTouches[0];
      const dx = touch.clientX - start.x;
      const dy = touch.clientY - start.y;
      const quick = Date.now() - start.at <= MAX_MS;
      start = null;
      if (!quick || Math.abs(dx) < MIN_DX || Math.abs(dx) < Math.abs(dy) * RATIO) return;
      const { ids: order, current: now, onChange: change } = state.current;
      const next = order[order.indexOf(now) + (dx < 0 ? 1 : -1)];
      if (next !== undefined) change(next);
    };
    const onCancel = () => { start = null; };
    el.addEventListener('touchstart', onStart, { passive: true });
    el.addEventListener('touchend', onEnd, { passive: true });
    el.addEventListener('touchcancel', onCancel, { passive: true });
    return () => {
      el.removeEventListener('touchstart', onStart);
      el.removeEventListener('touchend', onEnd);
      el.removeEventListener('touchcancel', onCancel);
    };
  }, [enabled]);

  return ref;
}

/** Направление анимации при переходе с вкладки from на to: 1 — новая выезжает справа, -1 — слева. */
export const tabDirection = (ids, from, to) => (ids.indexOf(to) >= ids.indexOf(from) ? 1 : -1);

/** Класс содержимого вкладки: въезжает со стороны новой вкладки; при первом показе — без движения. */
export const panelMotion = (direction) => {
  if (!direction) return '';
  return direction > 0 ? 'tab-from-right' : 'tab-from-left';
};
