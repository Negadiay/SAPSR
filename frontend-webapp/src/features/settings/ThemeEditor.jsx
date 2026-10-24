import { useState } from 'react';
import { StatusIcon } from '../../components/Icon';
import ColorWheel from '../../components/ColorWheel';
import { normalizeHex } from '../../lib/color';
import { PRESETS, PRESET_TITLES, THEME_FIELDS, readabilityIssues } from './customTheme';

// 4.5 → «4,5», 3 → «3»
const ratioText = (x) => String(Number(x.toFixed(1))).replace('.', ',');

// Код HEX: применяется, как только набраны шесть цифр; «#abc» раскрывается при выходе из поля, ошибка — откатывается.
function HexInput({ id, value, onChange }) {
  const [text, setText] = useState(value);
  const [shown, setShown] = useState(value);
  if (value !== shown) {
    setShown(value);
    setText(value);
  }
  const apply = (hex) => {
    setShown(hex);
    if (hex !== value) onChange(hex);
  };
  return (
    <input id={id} className="teacher-select theme-hex" value={text} maxLength={7} spellCheck={false}
      autoCapitalize="off" autoComplete="off" aria-invalid={normalizeHex(text) === null}
      onChange={(e) => {
        setText(e.target.value);
        if (/^#?[0-9a-f]{6}$/i.test(e.target.value.trim())) apply(normalizeHex(e.target.value));
      }}
      onBlur={() => {
        const hex = normalizeHex(text);
        setText(hex || value);
        if (hex) apply(hex);
      }} />
  );
}

// Цвета своей темы: основа одним нажатием, дальше — каждый цвет кругом или кодом.
export default function ThemeEditor({ colors, onChange, onPreset }) {
  const [picking, setPicking] = useState(null);
  const issues = readabilityIssues(colors);
  return (
    <div className="theme-editor">
      <div className="theme-presets" role="group" aria-label="Основа">
        {Object.keys(PRESETS).map((id) => (
          <button key={id} type="button" className="theme-preset" onClick={() => onPreset(id)}>
            <span className="theme-preset-swatch" aria-hidden="true"
              style={{ background: `linear-gradient(135deg, ${PRESETS[id].bg} 50%, ${PRESETS[id].button} 50%)` }} />
            {PRESET_TITLES[id]}
          </button>
        ))}
      </div>
      <ul className="theme-fields">
        {THEME_FIELDS.map(({ key, label }) => (
          <li key={key} className="theme-field">
            <div className="theme-field-row">
              <button type="button" className="theme-swatch" style={{ background: colors[key] }}
                aria-label={`${label}: цветовой круг`} aria-expanded={picking === key}
                onClick={() => setPicking(picking === key ? null : key)} />
              <label className="theme-field-label" htmlFor={`theme-${key}`}>{label}</label>
              <HexInput id={`theme-${key}`} value={colors[key]} onChange={(hex) => onChange(key, hex)} />
            </div>
            {picking === key && <ColorWheel value={colors[key]} label={label} onChange={(hex) => onChange(key, hex)} />}
          </li>
        ))}
      </ul>
      {issues.length > 0 && (
        <div className="theme-issues" role="status">
          {issues.map((issue) => (
            <p key={issue.what} className="theme-issue">
              <StatusIcon tone="warning" />
              <span>{issue.what} читается плохо: {ratioText(issue.ratio)} : 1, нужно от {ratioText(issue.min)} : 1</span>
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
