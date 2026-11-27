import { useEffect, useRef, useState } from 'react';
import Icon from '../../components/Icon';
import { hasMainButton } from '../../telegram/webApp';
import './tour.css';

const PAD = 6;        // подсветка шире элемента, px
const GAP = 12;       // между подсветкой и карточкой, px
const EDGE = 16;      // отступ карточки от края экрана, px
const WAIT_MS = 1500; // столько ждём, пока элемент появится (экран открывается, список загружается)

// Элемент шага: первый видимый с таким data-tour (у пустого списка виден блок «Работ пока нет»).
// Без разметки страницы (тесты) размеров нет ни у кого — тогда первый найденный.
const findTarget = (id) => {
  const all = [...document.querySelectorAll(`[data-tour="${id}"]`)];
  return all.find((el) => {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }) || all[0] || null;
};

const pressIfNeeded = (id) => {
  const el = id && document.querySelector(`[data-tour="${id}"]`);
  if (el && el.getAttribute('aria-selected') !== 'true') el.click();
};

// Высокий элемент (длинный список) подсвечивается верхней частью — карточке остаётся место под ним.
const TALL = 0.5;

const measure = (el) => {
  const r = el.getBoundingClientRect();
  const maxHeight = window.innerHeight * TALL;
  return { top: r.top, left: r.left, width: r.width, height: r.height > maxHeight ? maxHeight - Math.max(0, r.top) : r.height };
};

// Карточка — под элементом, если он в верхней половине экрана, иначе над ним; не уезжает за край.
const cardPlace = (rect) => {
  const vh = window.innerHeight;
  if (rect.top + rect.height / 2 < vh / 2) {
    const top = rect.top + rect.height + PAD + GAP;
    return top > vh * 0.55 ? { bottom: EDGE } : { top };
  }
  const bottom = vh - rect.top + PAD + GAP;
  return bottom > vh * 0.55 ? { top: EDGE } : { bottom };
};

/**
 * Обучение поверх экрана: затемнение с «окном» вокруг элемента и карточка с пояснением. Переходит между
 * экранами само (useTour), здесь — только поиск и подсветка элемента. Кнопки «Назад», «Далее», «Пропустить»;
 * с клавиатуры — стрелки и Esc. Нажатия мимо карточки ничего не делают: случайно не уйти и не нажать лишнего.
 */
export default function TourOverlay({ tour }) {
  const { step, index, total, next, prev, close } = tour;
  const native = step.target === 'primary' && Boolean(step.native) && hasMainButton();
  const [rect, setRect] = useState(null);
  const [waiting, setWaiting] = useState(true);
  const primary = useRef(null);

  // Новый шаг: нажать переключатель, дождаться элемента, прокрутить к нему и подсветить.
  useEffect(() => {
    let element = null;
    let cancelled = false;
    const update = () => { if (element?.isConnected) setRect(measure(element)); };
    const startedAt = Date.now();
    let timer;
    const poll = () => {
      if (cancelled) return;
      pressIfNeeded(step.press);
      element = step.target && !native ? findTarget(step.target) : null;
      if (element) {
        const tall = element.getBoundingClientRect().height > window.innerHeight * TALL;
        element.scrollIntoView?.({ block: tall ? 'start' : 'center' });
        update();
        setWaiting(false);
      } else if (!step.target || native || Date.now() - startedAt > WAIT_MS) {
        setRect(null);
        setWaiting(false);
      } else {
        timer = setTimeout(poll, 100);
      }
    };
    timer = setTimeout(poll, 0);
    window.addEventListener('resize', update);
    window.addEventListener('scroll', update, true);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      window.removeEventListener('resize', update);
      window.removeEventListener('scroll', update, true);
    };
  }, [step, native]);

  useEffect(() => {
    if (!waiting) primary.current?.focus({ preventScroll: true });
  }, [waiting, index]);

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') close();
      else if (e.key === 'ArrowRight') next();
      else if (e.key === 'ArrowLeft') prev();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const last = index + 1 >= total;
  const spot = rect && !waiting;
  let place = { top: '50%', transform: 'translateY(-50%)' };
  if (native) place = { bottom: EDGE };
  else if (spot) place = cardPlace(rect);

  return (
    <div className={`tour ${spot ? '' : 'tour-dim'}`} data-testid="tour">
      {spot && (
        <div className="tour-spot" aria-hidden="true" style={{
          top: rect.top - PAD,
          left: Math.max(4, rect.left - PAD),
          width: Math.min(window.innerWidth - 8, rect.width + PAD * 2),
          height: rect.height + PAD * 2,
        }} />
      )}
      {!waiting && (
        <div key={index} className="tour-card" role="dialog" aria-modal="true" aria-labelledby="tour-title"
          aria-describedby="tour-text" style={place}>
          <div className="tour-progress" aria-hidden="true">
            {Array.from({ length: total }, (_, i) => (
              <span key={i} className={`tour-dot ${i === index ? 'tour-dot-active' : ''} ${i < index ? 'tour-dot-done' : ''}`} />
            ))}
          </div>
          <p className="tour-count">Шаг {index + 1} из {total}</p>
          <p id="tour-title" className="tour-title">{step.title}</p>
          <p id="tour-text" className="tour-text">{native ? step.native : step.text}</p>
          <div className="tour-actions">
            <button type="button" className="link-btn tour-skip" onClick={close}>{last ? 'Закрыть' : 'Пропустить'}</button>
            {index > 0 && <button type="button" className="btn btn-secondary" onClick={prev}>Назад</button>}
            <button ref={primary} type="button" className="btn btn-primary" onClick={next}>
              {last ? 'Готово' : 'Далее'}
            </button>
          </div>
          {native && <Icon name="chevronDown" className="tour-arrow" />}
        </div>
      )}
    </div>
  );
}
