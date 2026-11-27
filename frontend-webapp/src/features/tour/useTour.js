import { useEffect, useState } from 'react';
import { ROOTS } from '../../navigation/useNavigator';
import { tourSteps } from './steps';

const seenKey = (role) => `sapsr_onboarded_${role}`;

const seen = (role) => {
  try { return Boolean(localStorage.getItem(seenKey(role))); } catch { return false; }
};

/**
 * Интерактивное обучение роли: шаги по экранам с подсветкой элементов. Само открывает нужный экран,
 * по окончании возвращает на главный. Первый вход — запускается само (после регистрации, у администратора —
 * при первом входе), потом — кнопкой «Обучение» в заголовке.
 */
export function useTour({ role, nav, ready, autoStart = false }) {
  const [index, setIndex] = useState(null);
  const [requested, setRequested] = useState(false);
  const steps = tourSteps(role);

  const show = (i) => {
    const step = steps[i];
    if (!step) return;
    if (nav.current?.screen !== step.screen) {
      nav.home();
      if (step.screen !== ROOTS[role]) nav.push(step.screen);
    }
    setIndex(i);
  };

  const close = () => {
    setIndex(null);
    try { localStorage.setItem(seenKey(role), '1'); } catch { /* покажется ещё раз — не страшно */ }
    nav.home();
  };

  // Первый вход под ролью: обучение начинается, когда главный экран уже открыт.
  const wanted = requested || autoStart;
  useEffect(() => {
    if (!wanted || !ready || seen(role) || steps.length === 0) return undefined;
    const timer = setTimeout(() => show(0), 500);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- show и steps новые на каждом рендере
  }, [wanted, ready, role]);

  const active = index !== null && Boolean(steps[index]);
  return {
    active,
    step: active ? steps[index] : null,
    index,
    total: steps.length,
    start: () => show(0),
    next: () => (index + 1 >= steps.length ? close() : show(index + 1)),
    prev: () => { if (index > 0) show(index - 1); },
    close,
    maybeStart: () => setRequested(true),
  };
}
