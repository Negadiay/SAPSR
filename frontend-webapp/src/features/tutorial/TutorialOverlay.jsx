import { useState, useEffect } from 'react';

export default function TutorialOverlay({ steps, step, onNext, onSkip, refs }) {
  const current = steps[step];
  const targetRef = current?.refKey ? refs[current.refKey] : null;
  const [rect, setRect] = useState(null);

  useEffect(() => {
    if (targetRef?.current) {
      const r = targetRef.current.getBoundingClientRect();
      setRect(r);
    } else {
      setRect(null);
    }
  }, [step, targetRef]);

  const PAD = 8;
  const TOOLTIP_W = Math.min(300, window.innerWidth - 24);
  const TOOLTIP_H = 210;
  const NAV_SAFE = 130;   // space for bottom nav
  const TOOLTIP_GAP = 16; // gap between spotlight and tooltip
  const vw = window.innerWidth;
  const vh = window.innerHeight;

  let tooltipStyle;
  if (rect) {
    const spotlightBottom = rect.bottom + PAD;
    const spotlightTop    = rect.top    - PAD;
    const spaceBelow = vh - spotlightBottom - NAV_SAFE;
    const spaceAbove = spotlightTop - TOOLTIP_GAP;

    let top;
    if (spaceBelow >= TOOLTIP_H) {
      // fits below the spotlight
      top = spotlightBottom + TOOLTIP_GAP;
    } else if (spaceAbove >= TOOLTIP_H) {
      // fits above the spotlight
      top = spotlightTop - TOOLTIP_GAP - TOOLTIP_H;
    } else {
      // neither fits — put above nav, may overlap spotlight
      top = vh - NAV_SAFE - TOOLTIP_H - 8;
    }
    top = Math.max(8, Math.min(top, vh - TOOLTIP_H - NAV_SAFE));

    let left = rect.left + rect.width / 2 - TOOLTIP_W / 2;
    left = Math.max(12, Math.min(left, vw - TOOLTIP_W - 12));
    tooltipStyle = { top, left, width: TOOLTIP_W };
  } else {
    tooltipStyle = { top: '35%', left: '50%', transform: 'translateX(-50%)', width: TOOLTIP_W };
  }

  return (
    // Clicking anywhere on overlay (except skip button) advances the tutorial
    <div className="tutorial-overlay" onClick={onNext}>
      {rect && (
        <div className="tutorial-spotlight" style={{
          top:    rect.top    - PAD,
          left:   rect.left   - PAD,
          width:  rect.width  + PAD * 2,
          height: rect.height + PAD * 2,
        }} />
      )}
      <div className="tutorial-tooltip" style={tooltipStyle}>
        <p>{current?.text}</p>
        <div className="tutorial-footer">
          <span className="tutorial-counter">{step + 1} / {steps.length}</span>
          <button className="tutorial-skip-btn"
            onClick={(e) => { e.stopPropagation(); onSkip(); }}>
            Пропустить
          </button>
          <span className="tutorial-hint">Нажмите в любое место →</span>
        </div>
      </div>
    </div>
  );
}
