import { useState } from 'react';
import { namePartProblem } from '../lib/names';

const FIELDS = [
  { key: 'lastName', label: 'Фамилия', autoComplete: 'family-name' },
  { key: 'firstName', label: 'Имя', autoComplete: 'given-name' },
  { key: 'middleName', label: 'Отчество', autoComplete: 'additional-name' },
];

/**
 * Фамилия, имя и отчество отдельными полями (решение 4) — для регистрации студента и «Моих данных».
 * value: { lastName, firstName, middleName, noMiddle }. Ошибка поля показывается после ухода из него.
 */
export default function NameFields({ value, onChange, idPrefix = 'name' }) {
  const [touched, setTouched] = useState({});
  return (
    <>
      {FIELDS.map(({ key, label, autoComplete }) => {
        const middle = key === 'middleName';
        const off = middle && value.noMiddle;
        const problem = off ? '' : namePartProblem(value[key], label);
        const shown = touched[key] && problem;
        const id = `${idPrefix}-${key}`;
        return (
          <div className="field" key={key}>
            <label className="field-label" htmlFor={id}>{label}</label>
            <input id={id} type="text" className="input" autoComplete={autoComplete} autoCapitalize="words"
              value={off ? '' : value[key]} disabled={off} maxLength={100}
              onChange={(e) => onChange({ ...value, [key]: e.target.value })}
              onBlur={() => setTouched((t) => ({ ...t, [key]: true }))}
              aria-invalid={Boolean(shown)} aria-describedby={shown ? `${id}-error` : undefined} />
            {shown && <p id={`${id}-error`} className="field-error">{problem}</p>}
            {middle && (
              <label className="check-row">
                <input type="checkbox" checked={Boolean(value.noMiddle)}
                  onChange={(e) => onChange({ ...value, noMiddle: e.target.checked })} />
                <span>Нет отчества</span>
              </label>
            )}
          </div>
        );
      })}
    </>
  );
}
