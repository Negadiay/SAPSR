import { downloadReport } from '../../api/submissions';
import { groupByFileName } from '../../lib/submissions';

// QUEUED и FAILED сервер начнёт отдавать вместе с протоколом очередей v2 (docs/contracts/queue-v2.md, раздел 6).
const iterationMark = (submission) => {
  if (submission.status === 'QUEUED' || submission.status === 'PROCESSING') return '⏳';
  if (submission.status === 'REJECTED') return '❌';
  if (submission.status === 'FAILED') return '⚠️';
  if (submission.teacher_verdict === 'REVISION') return '🔄';
  if (submission.status === 'SUCCESS') return '✅';
  return '•';
};

const autoStatusLabel = (s) => {
  if (s === 'QUEUED')     return '⏳ В очереди на проверку';
  if (s === 'PROCESSING') return '⏳ На авто-проверке';
  if (s === 'SUCCESS')    return '✅ Оформление ОК';
  if (s === 'REJECTED')   return '❌ Ошибки оформления';
  if (s === 'FAILED')     return '⚠️ Проверка не удалась — загрузите работу снова';
  return s;
};

// Отчёт есть только у проверенных работ: у работы в очереди или со сбоем проверки его нет.
const hasReport = (s) => s === 'SUCCESS' || s === 'REJECTED';

const verdictLabel = (v) => {
  if (v === 'APPROVED') return '✅ Принято преподавателем';
  if (v === 'REVISION')  return '🔄 На доработке';
  return null;
};

export default function StudentWorksTab({ works }) {
  const { submissions, refresh, withdrawing, requestWithdraw } = works;
  const workGroups = groupByFileName(submissions);

  return (
    <div className="tab-view">
      <h2 className="view-title">Мои работы</h2>
      <button className="refresh-btn" onClick={refresh}>Обновить</button>
      <div className="notif-window student-works-window">
        {submissions.length === 0 && <p className="notif-empty">Пока нет загруженных файлов</p>}
        {workGroups.map(group => {
          const s = group.latest;
          const timeline = [...group.items].reverse();
          return (
            <div key={group.key} className={`notif-line notif-line-stacked ${s.status === 'REJECTED' || s.status === 'FAILED' ? 'notif-error' : ''} ${s.status === 'SUCCESS' ? 'notif-success' : ''}`}>
              <div className="notif-row-main">
                <div className="notif-info">
                  <div className="notif-file-subject"><b>{group.fileName}</b></div>
                  <div className="iteration-chain">
                    {timeline.map((item, index) => (
                      <span key={item.id} className="iteration-step">
                        v{index + 1} {iterationMark(item)}
                      </span>
                    ))}
                    {s.teacher_verdict === 'APPROVED' && <span className="iteration-final">Принято преподавателем</span>}
                  </div>
                  <div className="notif-status">{autoStatusLabel(s.status)}</div>
                  {s.teacher_verdict && (
                    <div className={`notif-verdict ${s.teacher_verdict === 'APPROVED' ? 'verdict-ok' : 'verdict-revision'}`}>
                      {verdictLabel(s.teacher_verdict)}
                    </div>
                  )}
                  {s.teacher_comment && <div className="notif-comment">💬 {s.teacher_comment}</div>}
                </div>
                <div className="notif-btn-group">
                  {hasReport(s.status) && (
                    <button className="download-btn" title="Скачать отчёт" onClick={() => downloadReport(s.id)}>📥</button>
                  )}
                  {s.status === 'SUCCESS' && !s.teacher_verdict && (
                    <button className="withdraw-btn" aria-label="Отозвать работу" title="Отозвать работу" disabled={withdrawing === s.id}
                      onClick={() => requestWithdraw(s.id)}>
                      {withdrawing === s.id ? '⏳' : '↩'}
                    </button>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
