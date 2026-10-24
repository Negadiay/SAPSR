import { downloadSubmissionPdf } from '../../api/teacher';
import { formatDate } from '../../lib/format';
import Icon from '../../components/Icon';

const VerdictBadge = ({ verdict, short }) => (
  <span className={`history-verdict-badge with-icon ${verdict === 'APPROVED' ? 'history-badge-ok' : 'history-badge-revision'}`}>
    <Icon name={verdict === 'APPROVED' ? 'ok' : 'revision'} label={short ? (verdict === 'APPROVED' ? 'Принято' : 'Доработка') : undefined} />
    {!short && <span>{verdict === 'APPROVED' ? 'Принято' : 'Доработка'}</span>}
  </span>
);

export default function HistoryTab({ history }) {
  const { groups, refresh, expandedKey, toggleExpanded } = history;

  return (
    <div className="tab-view">
      <h2 className="view-title">История проверок</h2>
      <button className="refresh-btn" onClick={refresh}>Обновить</button>
      <div className="notif-window" style={{ height: '65vh' }}>
        {groups.length === 0 && (
          <p className="notif-empty">История проверок пуста</p>
        )}
        {groups.map(group => {
          const s = group.latest;
          const isExpanded = expandedKey === group.key;
          const hasVersions = group.items.length > 1;
          return (
            <div key={group.key} className="ts-card-compact history-card">
              {/* Основная карточка (последняя версия) */}
              <div className="ts-card-main history-card-main"
                onClick={() => hasVersions && toggleExpanded(group.key)}>
                <div className="ts-row-top">
                  <span className="ts-student">{s.student_name || 'Студент'}</span>
                  <div className="history-card-right">
                    <VerdictBadge verdict={s.teacher_verdict} />
                    {hasVersions && (
                      <span className="ts-chevron"><Icon name={isExpanded ? 'chevronUp' : 'chevronDown'} /></span>
                    )}
                  </div>
                </div>
                <div className="ts-row-bottom">
                  <span className="ts-file">{s.file_name}</span>
                  <span className="ts-date">{formatDate(s.created_at)}</span>
                </div>
                {s.teacher_comment && (
                  <div className="ts-comment with-icon"><Icon name="comment" /><span>{s.teacher_comment}</span></div>
                )}
                <div className="history-actions">
                  <button className="download-btn-sm with-icon" onClick={(e) => { e.stopPropagation(); downloadSubmissionPdf(s.id); }}>
                    <Icon name="file" /><span>Скачать</span>
                  </button>
                  {hasVersions && !isExpanded && (
                    <span className="history-versions-hint">{group.items.length} версии</span>
                  )}
                </div>
              </div>
              {/* Предыдущие версии */}
              {isExpanded && group.items.slice(1).map((ver, idx) => (
                <div key={ver.id} className="history-version-row">
                  <div className="ts-row-top">
                    <span className="ts-file" style={{ flex: 1 }}>v{group.items.length - 1 - idx} · {formatDate(ver.created_at)}</span>
                    <VerdictBadge verdict={ver.teacher_verdict} short />
                  </div>
                  {ver.teacher_comment && (
                    <div className="ts-comment with-icon"><Icon name="comment" /><span>{ver.teacher_comment}</span></div>
                  )}
                  <button className="download-btn-sm with-icon" style={{ marginTop: 6 }}
                    onClick={() => downloadSubmissionPdf(ver.id)}>
                    <Icon name="file" /><span>Скачать v{group.items.length - 1 - idx}</span>
                  </button>
                </div>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}
