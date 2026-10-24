import { downloadReport } from '../../api/submissions';
import { groupByFileName } from '../../lib/submissions';
import Icon, { StatusIcon } from '../../components/Icon';
import TeacherComments from './TeacherComments';

// Статусы и ход проверки — протокол очередей v2 (docs/contracts/queue-v2.md, раздел 6).
// tone — цвет и иконка состояния; текст рядом всегда есть, цвет не единственный признак.
const STATUS = {
  QUEUED:     { tone: 'pending', short: 'проверяется', text: 'В очереди на проверку' },
  PROCESSING: { tone: 'pending', short: 'проверяется', text: 'На авто-проверке' },
  SUCCESS:    { tone: 'ok',      short: 'оформление ОК', text: 'Оформление ОК' },
  REJECTED:   { tone: 'error',   short: 'ошибки оформления',
                text: 'Ошибки оформления — работа у преподавателя, загрузите исправленную версию' },
  FAILED:     { tone: 'warning', short: 'проверка не удалась', text: 'Проверка не удалась — загрузите работу снова' },
};

// Метка версии в цепочке: вердикт преподавателя важнее итога автопроверки. Текста рядом нет — у иконки подпись.
function IterationMark({ submission }) {
  if (submission.teacher_verdict === 'REVISION') {
    return <Icon name="revision" label="на доработке" className="status-icon status-warning" />;
  }
  const status = STATUS[submission.status];
  return status ? <StatusIcon tone={status.tone} label={status.short} /> : null;
}

// Отчёт есть только у проверенных работ: у работы в очереди или со сбоем проверки его нет.
const hasReport = (s) => s === 'SUCCESS' || s === 'REJECTED';

const VERDICTS = {
  APPROVED: { icon: 'ok', text: 'Принято преподавателем' },
  REVISION: { icon: 'revision', text: 'На доработке' },
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
          const status = STATUS[s.status];
          const verdict = VERDICTS[s.teacher_verdict];
          return (
            <div key={group.key} className={`notif-line notif-line-stacked ${s.status === 'REJECTED' || s.status === 'FAILED' ? 'notif-error' : ''} ${s.status === 'SUCCESS' ? 'notif-success' : ''}`}>
              <div className="notif-row-main">
                <div className="notif-info">
                  <div className="notif-file-subject"><b>{group.fileName}</b></div>
                  <div className="iteration-chain">
                    {timeline.map((item, index) => (
                      <span key={item.id} className="iteration-step with-icon">
                        v{index + 1} <IterationMark submission={item} />
                      </span>
                    ))}
                    {s.teacher_verdict === 'APPROVED' && <span className="iteration-final">Принято преподавателем</span>}
                  </div>
                  <div className="notif-status with-icon">
                    {status ? <><StatusIcon tone={status.tone} /><span>{status.text}</span></> : s.status}
                  </div>
                  {s.content_status === 'PENDING' && s.status !== 'QUEUED' && (
                    <div className="notif-status with-icon">
                      <Icon name="content" /><span>Содержание ещё проверяется — итог придёт в бот</span>
                    </div>
                  )}
                  {verdict && (
                    <div className={`notif-verdict with-icon ${s.teacher_verdict === 'APPROVED' ? 'verdict-ok' : 'verdict-revision'}`}>
                      <Icon name={verdict.icon} /><span>{verdict.text}</span>
                    </div>
                  )}
                  {s.teacher_comment && (
                    <div className="notif-comment with-icon"><Icon name="comment" /><span>{s.teacher_comment}</span></div>
                  )}
                  {s.has_review_file && <TeacherComments submission={s} />}
                </div>
                <div className="notif-btn-group">
                  {hasReport(s.status) && (
                    <button className="download-btn" title="Скачать отчёт" onClick={() => downloadReport(s.id)}>
                      <Icon name="download" label="Скачать отчёт" />
                    </button>
                  )}
                  {s.status === 'SUCCESS' && !s.teacher_verdict && (
                    <button className="withdraw-btn" aria-label="Отозвать работу" title="Отозвать работу" disabled={withdrawing === s.id}
                      onClick={() => requestWithdraw(s.id)}>
                      <Icon name={withdrawing === s.id ? 'loading' : 'withdraw'} />
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
