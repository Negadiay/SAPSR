import { useState } from 'react';
import Icon from './Icon';
import { ORIGIN_LABELS, findingPlace, isError, severityLabel, splitFindings } from '../lib/findings';

const MAX_SHOWN = 8;

function Finding({ finding, forTeacher }) {
  const [open, setOpen] = useState(false);
  const details = [
    ['Найдено', finding.found],
    ['Нужно', finding.expected && finding.expected !== finding.message ? finding.expected : null],
    ['Как исправить', finding.fix],
  ].filter(([, text]) => text);
  const content = finding.origin && finding.origin !== 'symbolic';
  // Содержательные замечания сдачу не блокируют — уровень «ошибка» у них только пугал бы.
  const meta = [content ? '' : severityLabel(finding), findingPlace(finding)];
  if (content) meta.push(ORIGIN_LABELS[finding.origin] || finding.origin);
  if (finding.needs_teacher_review) meta.push(forTeacher ? 'на ваше решение' : 'решит преподаватель');
  const tone = content ? 'review' : isError(finding) ? 'error' : 'warning';
  return (
    <li className={`finding finding-${tone}`}>
      <p className="finding-message">{finding.message || finding.title}</p>
      <p className="finding-meta">{meta.filter(Boolean).join(' · ')}</p>
      {details.length > 0 && (
        <>
          <button type="button" className="link-btn" aria-expanded={open} onClick={() => setOpen(!open)}>
            {open ? 'Скрыть' : 'Подробнее'}
            <Icon name={open ? 'chevronUp' : 'chevronDown'} />
          </button>
          {open && (
            <dl className="finding-details">
              {details.map(([name, text]) => (
                <div key={name}><dt>{name}</dt><dd>{text}</dd></div>
              ))}
            </dl>
          )}
        </>
      )}
    </li>
  );
}

function Group({ title, note, findings, forTeacher }) {
  const [all, setAll] = useState(false);
  if (!findings.length) return null;
  const shown = all ? findings : findings.slice(0, MAX_SHOWN);
  return (
    <section className="finding-group">
      <h3 className="section-title">{title} · {findings.length}</h3>
      {note && <p className="hint">{note}</p>}
      <ul className="finding-list">
        {shown.map((f, i) => <Finding key={f.id || i} finding={f} forTeacher={forTeacher} />)}
      </ul>
      {findings.length > shown.length && (
        <button type="button" className="link-btn" onClick={() => setAll(true)}>
          Показать все ({findings.length})
        </button>
      )}
    </section>
  );
}

// Замечания автоматической проверки: оформление и содержание по отдельности.
export default function FindingList({ raw, forTeacher = false }) {
  const { format, content } = splitFindings(raw);
  if (!format.length && !content.length) return null;
  return (
    <div className="findings">
      <Group title="Оформление" findings={format} forTeacher={forTeacher} />
      <Group title="Содержание" findings={content} forTeacher={forTeacher}
        note={forTeacher ? 'Сдачу не блокируют. Найдены нейросетью — проверьте.' : 'Сдаче не мешают; решение — за преподавателем.'} />
    </div>
  );
}
