import ReviewFileBlock from './ReviewFileBlock';
import { downloadSubmissionPdf } from '../../api/teacher';
import { downloadReport } from '../../api/submissions';
import { formatDate } from '../../lib/format';
import { ORIGIN_LABELS, summarizeFindings } from '../../lib/findings';
import Icon, { StatusIcon } from '../../components/Icon';

const MAX_CONTENT_FINDINGS = 5;

// Содержательная проверка не закончилась (queue-v2.md, раздел 6): отклонённая работа видна сразу после
// проверки оформления, а содержание может не успеть или не удаться.
const CONTENT_STATE = {
  PENDING: 'Содержание ещё проверяется',
  TIMEOUT: 'Содержание не проверено — не успело по времени',
  FAILED: 'Содержание проверить не удалось',
};

// Итог автоматической проверки одной строкой: оформление и содержание.
function AutoCheckLine({ submission, summary }) {
  const rejected = submission.status === 'REJECTED';
  const contentState = CONTENT_STATE[submission.content_status];
  const content = contentState || (summary.content.length > 0
    ? `Содержание: ${summary.content.length}${summary.needsReview > 0 ? ` (на вашу проверку: ${summary.needsReview})` : ''}`
    : null);
  return (
    <div className="ts-autocheck">
      <span className={`with-icon ${rejected ? 'ts-autocheck-bad' : 'ts-autocheck-ok'}`}>
        <StatusIcon tone={rejected ? 'error' : 'ok'} />
        <span>{rejected ? `Ошибки оформления: ${summary.formatErrors}` : 'Оформление ОК'}</span>
      </span>
      {content && (
        <span className="ts-autocheck-content with-icon"><Icon name="content" /><span>{content}</span></span>
      )}
    </div>
  );
}

function ContentFindings({ findings }) {
  if (findings.length === 0) return null;
  return (
    <div className="ts-findings">
      <div className="ts-findings-title">Замечания по содержанию</div>
      {findings.slice(0, MAX_CONTENT_FINDINGS).map((f, index) => (
        <div key={index} className={`ts-finding ${f.needs_teacher_review ? 'ts-finding-review' : ''}`}>
          <div>{f.message}</div>
          <div className="ts-finding-meta">
            {f.page != null && `стр. ${f.page} · `}
            {ORIGIN_LABELS[f.origin] || f.origin}
            {f.needs_teacher_review && ' · требует вашей проверки'}
          </div>
        </div>
      ))}
      {findings.length > MAX_CONTENT_FINDINGS && (
        <div className="ts-finding-meta">И ещё {findings.length - MAX_CONTENT_FINDINGS} — в отчёте проверки.</div>
      )}
    </div>
  );
}

export default function QueueTab({ queue, searchRef, listRef }) {
  const {
    pending, filtered, refresh, search, setSearch,
    expandedId, toggleExpanded, canSendRevision, verdictLoading, submitVerdict,
  } = queue;

  return (
    <div className="tab-view">
      <h2 className="view-title">Работы студентов</h2>
      <button className="refresh-btn" onClick={refresh}>Обновить</button>
      <input className="teacher-search" type="search"
        placeholder="Поиск по группе, студенту или файлу..."
        ref={searchRef}
        value={search} onChange={(e) => setSearch(e.target.value)} />
      <div className="notif-window" ref={listRef}>
        {pending.length === 0 && <p className="notif-empty">Нет работ, ожидающих проверки</p>}
        {pending.length > 0 && filtered.length === 0 && (
          <p className="notif-empty">По этому запросу работ не найдено</p>
        )}
        {filtered.map(s => {
          const isExpanded = expandedId === s.id;
          const summary = summarizeFindings(s.format_errors);
          return (
            <div key={s.id} className={`ts-card-compact ${isExpanded ? 'ts-card-expanded' : ''}`}>
              <div className="ts-card-main" onClick={() => toggleExpanded(s.id)}>
                <div className="ts-row-top">
                  <span className="ts-student">{s.student_name || 'Студент'}</span>
                  <span className="ts-date" title="Когда пришла эта версия">{formatDate(s.created_at)}</span>
                </div>
                <div className="ts-row-bottom">
                  <span className="ts-file">{s.file_name}</span>
                  <span className="ts-chevron"><Icon name={isExpanded ? 'chevronUp' : 'chevronDown'} /></span>
                </div>
                {s.version > 1 && (
                  // Решение 9: срок сдачи засчитывается по первой версии.
                  <div className="ts-version">Версия {s.version} · первая пришла {formatDate(s.first_submitted_at)}</div>
                )}
                <AutoCheckLine submission={s} summary={summary} />
              </div>
              {isExpanded && (
                <div className="ts-actions">
                  <div className="ts-downloads">
                    <button className="download-btn-sm with-icon" onClick={() => downloadSubmissionPdf(s.id)}>
                      <Icon name="file" /><span>Скачать PDF</span>
                    </button>
                    <button className="download-btn-sm with-icon" onClick={() => downloadReport(s.id)}>
                      <Icon name="report" /><span>Отчёт проверки</span>
                    </button>
                  </div>
                  <ContentFindings findings={summary.content} />
                  <ReviewFileBlock submission={s} queue={queue} />
                  <button className="approve-btn with-icon" disabled={verdictLoading}
                    onClick={() => submitVerdict(s.id, 'APPROVED')}>
                    <Icon name="ok" /><span>Принять</span>
                  </button>
                  <button className="revision-btn with-icon" disabled={verdictLoading || !canSendRevision(s)}
                    onClick={() => submitVerdict(s.id, 'REVISION')}>
                    <Icon name="revision" /><span>На доработку</span>
                  </button>
                  {!canSendRevision(s) && (
                    <div className="ts-finding-meta">Чтобы отправить на доработку, загрузите работу с вашими пометками.</div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
