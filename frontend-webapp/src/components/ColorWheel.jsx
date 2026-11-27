import { useRef, useState } from 'react';
import { hexToHsv, hsvToHex } from '../lib/color';
import './ColorWheel.css';

const clamp = (x, min, max) => Math.min(max, Math.max(min, x));

/**
 * Цветовой круг: угол — оттенок, расстояние от центра — насыщенность; ползунок под кругом — яркость.
 * Свой, а не <input type="color">: системный выбор цвета в Telegram на разных устройствах разный, а где-то его нет.
 * С клавиатуры: ←/→ — оттенок, ↑/↓ — насыщенность (с Shift — крупнее шаг).
 */
export default function ColorWheel({ value, label, onChange }) {
  const wheelRef = useRef(null);
  const dragging = useRef(false);
  const [state, setState] = useState(() => ({ hex: value, hsv: hexToHsv(value) }));
  // Цвет поменяли снаружи (код HEX, основа) — круг берёт его. Оттенок серого круг помнит сам: из HEX его не узнать.
  if (state.hex !== value) setState({ hex: value, hsv: hexToHsv(value) });
  const { h, s, v } = state.hsv;

  const update = (hsv) => {
    const hex = hsvToHex(hsv);
    setState({ hex, hsv });
    if (hex !== value) onChange(hex);
  };

  // На чёрном круг не видно — выбор оттенка сразу делает цвет ярким.
  const visible = v < 0.05 ? 1 : v;

  const pick = (e) => {
    const rect = wheelRef.current.getBoundingClientRect();
    const r = rect.width / 2;
    if (!r) return;
    const dx = e.clientX - rect.left - r;
    const dy = e.clientY - rect.top - r;
    update({ h: ((Math.atan2(dx, -dy) * 180) / Math.PI + 360) % 360, s: Math.min(1, Math.hypot(dx, dy) / r), v: visible });
  };

  const onKeyDown = (e) => {
    const step = e.shiftKey ? 15 : 5;
    const moves = {
      ArrowRight: { h: (h + step) % 360 },
      ArrowLeft: { h: (h - step + 360) % 360 },
      ArrowUp: { s: clamp(s + step / 100, 0, 1) },
      ArrowDown: { s: clamp(s - step / 100, 0, 1) },
    };
    if (!moves[e.key]) return;
    e.preventDefault();
    update({ h, s, v: visible, ...moves[e.key] });
  };

  const angle = (h * Math.PI) / 180;
  return (
    <div className="color-wheel-picker">
      <div ref={wheelRef} className="color-wheel" tabIndex={0} role="slider"
        aria-label={`${label}: оттенок и насыщенность`} aria-valuemin={0} aria-valuemax={360}
        aria-valuenow={Math.round(h)} aria-valuetext={`оттенок ${Math.round(h)}°, насыщенность ${Math.round(s * 100)} %`}
        onPointerDown={(e) => { dragging.current = true; e.currentTarget.setPointerCapture?.(e.pointerId); pick(e); }}
        onPointerMove={(e) => { if (dragging.current) pick(e); }}
        onPointerUp={() => { dragging.current = false; }}
        onPointerCancel={() => { dragging.current = false; }}
        onKeyDown={onKeyDown}>
        <div className="color-wheel-shade" style={{ opacity: 1 - v }} />
        <span className="color-wheel-marker" style={{
          left: `${50 + 50 * s * Math.sin(angle)}%`, top: `${50 - 50 * s * Math.cos(angle)}%`, background: value,
        }} />
      </div>
      <input type="range" className="color-brightness" min="0" max="100" value={Math.round(v * 100)}
        aria-label={`${label}: яркость`}
        style={{ background: `linear-gradient(to right, #000, ${hsvToHex({ h, s, v: 1 })})` }}
        onChange={(e) => update({ h, s, v: Number(e.target.value) / 100 })} />
    </div>
  );
}
