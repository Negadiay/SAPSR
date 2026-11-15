import { useEffect, useState } from 'react';
import FindingList from './FindingList';
import Icon from './Icon';
import StatusLine from './StatusLine';
import { deleteFindingFeedback, fetchReportV2, saveFindingFeedback } from '../api/report';
import { findingPlace, isError, severityLabel } from '../lib/findings';
import {
  ORIGINS, REQUIREMENT_STATUS, REVIEW_THRESHOLD, STAGE_LABELS, confidenceLabel, evidencePlace, formatMs, isContent, isContradiction,
  modeLabel, skippedMessages, summarize,
} from '../lib/report';
import './report.css';

const MAX_SHOWN = 8;

// Фрагменты текста, на которые опирается замечание. У противоречия — рядом (на узком экране — друг под другом).
function Evidence({ finding }) {
  const quotes = (finding.evidence || []).filter((e) => e.quote);
  if (!quotes.length) return null;
  const sideBySide = isContradiction(finding);
  return (
    <div className={sideBySide ? 'evidence evidence-compare' : 'evidence'}>
      {quotes.map((e, i) => (
        <figure key={e.block_id || i} className="evidence-item">
          <figcaption>{evidencePlace(e) || 'фрагмент'}</figcaption>
          <blockquote>{e.quote}</blockquote>
        </figure>
      ))}
    </div>
  );
}

function Finding({ finding, forTeacher, mark, onMark }) {
  const [open, setOpen] = useState(false);
  const [originOpen, setOriginOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const content = isContent(finding);
  const origin = ORIGINS[finding.origin];
  const tone = content ? 'review' : isError(finding) ? 'error' : 'warning';
  const meta = [content ? '' : severityLabel(finding), findingPlace(finding)].filter(Boolean);
  const review = finding.needs_teacher_review ? (forTeacher ? 'на ваше решение' : 'решит преподаватель') : '';
  const source = finding.source ? [finding.source.doc, finding.source.clause].filter(Boolean).join(', ') : '';
  const details = [
    ['Нужно', finding.expected && finding.expected !== finding.message ? finding.expected : null],
    ['Найдено', finding.found],
    ['Как исправить', finding.fix],
    ['Источник', source],
  ].filter(([, text]) => text);
  const hasMore = details.length > 0 || (finding.evidence || []).some((e) => e.quote);
  const collapsed = mark === 'incorrect' && !expanded;

  const marks = forTeacher && onMark && (
    <div className="finding-marks" role="group" aria-label="Отметка замечания">
      {[['correct', 'Верно'], ['incorrect', 'Неверно']].map(([value, label]) => (
        <button key={value} type="button" aria-pressed={mark === value}
          className={`mark-btn ${mark === value ? `mark-btn-${value}` : ''}`}
          onClick={() => { setExpanded(false); onMark(finding.id, mark === value ? null : value); }}>
          {label}
        </button>
      ))}
    </div>
  );

  if (collapsed) {
    return (
      <li className="finding finding-dismissed">
        <p className="finding-meta">Отмечено как неверное</p>
        <p className="finding-message">{finding.message || finding.title}</p>
        <div className="finding-foot">
          <button type="button" className="link-btn" onClick={() => setExpanded(true)}>Показать</button>
          {marks}
        </div>
      </li>
    );
  }

  return (
    <li className={`finding finding-${tone}`}>
      <p className="finding-message">{finding.message || finding.title}</p>
      <p className="finding-meta">
        {meta.join(' · ')}
        {meta.length > 0 && origin ? ' · ' : ''}
        {origin && (
          <button type="button" className="origin-btn" aria-expanded={originOpen} onClick={() => setOriginOpen(!originOpen)}>
            {origin.label}
          </button>
        )}
        {content && confidenceLabel(finding.confidence) ? ` · ${confidenceLabel(finding.confidence)}` : ''}
        {review && !(content && typeof finding.confidence === 'number' && finding.confidence < REVIEW_THRESHOLD) ? ` · ${review}` : ''}
      </p>
      {originOpen && origin && <p className="hint origin-note">{origin.note}</p>}
      {isContradiction(finding) && <Evidence finding={finding} />}
      {hasMore && (
        <>
          <button type="button" className="link-btn" aria-expanded={open} onClick={() => setOpen(!open)}>
            {open ? 'Скрыть' : 'Подробнее'}
            <Icon name={open ? 'chevronUp' : 'chevronDown'} />
          </button>
          {open && (
            <>
              <dl className="finding-details">
                {details.map(([name, text]) => (
                  <div key={name}><dt>{name}</dt><dd>{text}</dd></div>
                ))}
              </dl>
              {!isContradiction(finding) && <Evidence finding={finding} />}
            </>
          )}
        </>
      )}
      {marks && <div className="finding-foot">{marks}</div>}
    </li>
  );
}

function Group({ title, note, findings, forTeacher, marks, onMark }) {
  const [all, setAll] = useState(false);
  if (!findings.length) return null;
  const shown = all ? findings : findings.slice(0, MAX_SHOWN);
  return (
    <section className="finding-group">
      <h3 className="section-title">{title} · {findings.length}</h3>
      {note && <p className="hint">{note}</p>}
      <ul className="finding-list">
        {shown.map((f, i) => (
          <Finding key={f.id || i} finding={f} forTeacher={forTeacher} mark={marks?.[f.id]} onMark={onMark} />
        ))}
      </ul>
      {findings.length > shown.length && (
        <button type="button" className="link-btn" onClick={() => setAll(true)}>Показать все ({findings.length})</button>
      )}
    </section>
  );
}

// Что изменилось по сравнению с прошлой версией работы (решение 17).
function Comparison({ comparison }) {
  const [open, setOpen] = useState(false);
  if (!comparison) return null;
  const parts = [['Исправлено', comparison.fixed || []], ['Осталось', comparison.remaining || []], ['Появилось', comparison.new || []]];
  return (
    <section className="comparison">
      <button type="button" className="comparison-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span>{parts.map(([name, items]) => `${name}: ${items.length}`).join(' · ')}</span>
        <Icon name={open ? 'chevronUp' : 'chevronDown'} />
      </button>
      <p className="hint">По сравнению с прошлой версией работы.</p>
      {open && parts.map(([name, items]) => items.length > 0 && (
        <div key={name} className="comparison-list">
          <h4>{name}</h4>
          <ul>
            {items.map((item) => (
              <li key={item.requirement_id}>
                {item.title || item.requirement_id}
                {item.count_before !== item.count_after && item.count_before > 0 && item.count_after > 0
                  ? ` (было ${item.count_before}, стало ${item.count_after})` : ''}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}

// Карта покрытия требований семестра: требование, статус словами, цитата-свидетельство.
function Requirements({ requirements, open }) {
  if (!requirements?.length) return null;
  const done = requirements.filter((r) => r.status === 'satisfied').length;
  return (
    <details className="report-details" open={open}>
      <summary>Требования семестра: выполнено {done} из {requirements.length}</summary>
      <ul className="requirement-list">
        {requirements.map((r) => {
          const status = REQUIREMENT_STATUS[r.status] || { label: r.status, tone: 'muted' };
          const quote = (r.evidence || []).find((e) => e.quote);
          return (
            <li key={r.id} className="requirement">
              <p className="requirement-title">{r.title}</p>
              <StatusLine tone={status.tone}>{status.label}</StatusLine>
              {r.note && <p className="hint">{r.note}</p>}
              {quote && (
                <figure className="evidence-item">
                  <figcaption>{evidencePlace(quote) || 'фрагмент'}</figcaption>
                  <blockquote>{quote.quote}</blockquote>
                </figure>
              )}
            </li>
          );
        })}
      </ul>
    </details>
  );
}

function CheckInfo({ report }) {
  const pipeline = report.pipeline || {};
  const models = (pipeline.models || []).map((m) => [
    m.role === 'llm' ? 'Языковая модель' : 'Разметка документа',
    [m.name, m.version ? `версия ${m.version}` : '', m.quantization, m.cloud ? 'облачная' : ''].filter(Boolean).join(', '),
  ]);
  const stages = (pipeline.stages || []).filter((s) => typeof s.ms === 'number');
  return (
    <details className="report-details">
      <summary>Сведения о проверке</summary>
      <dl className="finding-details">
        <div><dt>Режим</dt><dd>{modeLabel(pipeline.mode)}</dd></div>
        {models.map(([name, text]) => <div key={name}><dt>{name}</dt><dd>{text}</dd></div>)}
        {pipeline.kb_version && <div><dt>Версия базы знаний</dt><dd>{pipeline.kb_version}</dd></div>}
        {report.profile?.title && <div><dt>Профиль требований</dt><dd>{report.profile.title}</dd></div>}
        {stages.length > 0 && (
          <div>
            <dt>Время этапов</dt>
            <dd>{stages.map((s) => `${STAGE_LABELS[s.stage] || s.stage} — ${formatMs(s.ms)}`).join('; ')}</dd>
          </div>
        )}
      </dl>
    </details>
  );
}

/**
 * Замечания автоматической проверки по отчёту v2 (api-v1.md, раздел 19) — общий блок экранов студента
 * и преподавателя. Пока отчёта нет (старая работа, идёт загрузка, сбой) — прежний список по format_errors.
 */
export default function ReportView({ submission, forTeacher = false }) {
  const [state, setState] = useState({ id: null, data: null });
  const [marks, setMarks] = useState({});
  const [markError, setMarkError] = useState('');
  // Отчёт дополняется, пока идёт проверка содержания, — перечитываем при смене состояния работы.
  const version = `${submission.id}:${submission.status}:${submission.content_status}`;

  useEffect(() => {
    let cancelled = false;
    fetchReportV2(submission.id).then((data) => {
      if (cancelled) return;
      setState({ id: submission.id, data });
      setMarks(data?.feedback || {});
    });
    return () => { cancelled = true; };
  }, [submission.id, version]);

  const data = state.id === submission.id ? state.data : null;
  const report = data?.report;
  if (!report || !Array.isArray(report.findings)) {
    return <FindingList raw={submission.format_errors} forTeacher={forTeacher} />;
  }

  const onMark = async (findingId, verdict) => {
    const previous = marks;
    setMarkError('');
    setMarks((current) => {
      const next = { ...current };
      if (verdict) next[findingId] = verdict;
      else delete next[findingId];
      return next;
    });
    const ok = verdict
      ? await saveFindingFeedback(submission.id, findingId, verdict)
      : await deleteFindingFeedback(submission.id, findingId);
    if (!ok) { setMarks(previous); setMarkError('Не удалось сохранить отметку. Попробуйте ещё раз.'); }
  };

  const findings = report.findings;
  const format = findings.filter((f) => !isContent(f)).sort((a, b) => Number(isError(b)) - Number(isError(a)));
  const content = findings.filter(isContent);
  const counts = summarize(findings);
  const skipped = skippedMessages(report);
  const canMark = forTeacher && Boolean(data.feedback);

  return (
    <div className="findings report-view">
      <Comparison comparison={data.comparison} />

      <section className="report-summary" aria-label="Итог автоматической проверки">
        <p>
          Оформление: {counts.format} · Структура: {counts.structure} · Содержание: {counts.content}
        </p>
        {counts.review > 0 && (
          <p className="hint">{forTeacher ? 'На ваше решение' : 'Решит преподаватель'}: {counts.review}</p>
        )}
        {findings.length === 0 && <StatusLine tone="ok">Замечаний нет</StatusLine>}
      </section>

      <Group title="Оформление и структура" findings={format} forTeacher={forTeacher}
        marks={marks} onMark={canMark ? onMark : null} />
      <Group title="Содержание" findings={content} forTeacher={forTeacher} marks={marks} onMark={canMark ? onMark : null}
        note={forTeacher ? 'Сдачу не блокируют. Найдены с участием нейросети — проверьте.' : 'Сдаче не мешают; решение — за преподавателем.'} />
      {markError && <StatusLine tone="error">{markError}</StatusLine>}

      <Requirements requirements={report.requirements} open={forTeacher} />

      {skipped.length > 0 && (
        <section className="finding-group">
          <h3 className="section-title">Не проверено</h3>
          <ul className="skipped-list">
            {skipped.map((message) => <li key={message}>{message}</li>)}
          </ul>
        </section>
      )}

      <CheckInfo report={report} />
    </div>
  );
}
