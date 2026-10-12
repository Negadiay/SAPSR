import { downloadSubmissionPdf } from '../../api/teacher';
import { formatDate } from '../../lib/format';

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
                    <span className={`history-verdict-badge ${s.teacher_verdict === 'APPROVED' ? 'history-badge-ok' : 'history-badge-revision'}`}>
                      {s.teacher_verdict === 'APPROVED' ? '✅ Принято' : '🔄 Доработка'}
                    </span>
                    {hasVersions && (
                      <span className="ts-chevron">{isExpanded ? '▲' : '▼'}</span>
                    )}
                  </div>
                </div>
                <div className="ts-row-bottom">
                  <span className="ts-file">{s.file_name}</span>
                  <span className="ts-date">{formatDate(s.created_at)}</span>
                </div>
                {s.teacher_comment && <div className="ts-comment">💬 {s.teacher_comment}</div>}
                <div className="history-actions">
                  <button className="download-btn-sm" onClick={(e) => { e.stopPropagation(); downloadSubmissionPdf(s.id); }}>
                    📄 Скачать
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
                    <span className={`history-verdict-badge ${ver.teacher_verdict === 'APPROVED' ? 'history-badge-ok' : 'history-badge-revision'}`}>
                      {ver.teacher_verdict === 'APPROVED' ? '✅' : '🔄'}
                    </span>
                  </div>
                  {ver.teacher_comment && <div className="ts-comment">💬 {ver.teacher_comment}</div>}
                  <button className="download-btn-sm" style={{ marginTop: 6 }}
                    onClick={() => downloadSubmissionPdf(ver.id)}>
                    📄 Скачать v{group.items.length - 1 - idx}
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
