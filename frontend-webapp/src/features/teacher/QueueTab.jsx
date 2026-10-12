import RevisionForm from './RevisionForm';
import { downloadSubmissionPdf } from '../../api/teacher';
import { formatDate } from '../../lib/format';

export default function QueueTab({ queue, searchRef, listRef }) {
  const {
    pending, filtered, refresh, search, setSearch,
    expandedId, toggleExpanded, revisionId, openRevision, verdictLoading, submitVerdict,
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
          return (
            <div key={s.id} className={`ts-card-compact ${isExpanded ? 'ts-card-expanded' : ''}`}>
              <div className="ts-card-main" onClick={() => toggleExpanded(s.id)}>
                <div className="ts-row-top">
                  <span className="ts-student">{s.student_name || 'Студент'}</span>
                  <span className="ts-date">{formatDate(s.created_at)}</span>
                </div>
                <div className="ts-row-bottom">
                  <span className="ts-file">{s.file_name}</span>
                  <span className="ts-chevron">{isExpanded ? '▲' : '▼'}</span>
                </div>
              </div>
              {isExpanded && (
                <div className="ts-actions">
                  <button className="download-btn-sm" onClick={() => downloadSubmissionPdf(s.id)}>📄 Скачать PDF</button>
                  <button className="approve-btn" disabled={verdictLoading}
                    onClick={() => submitVerdict(s.id, 'APPROVED')}>
                    ✅ Принять
                  </button>
                  {revisionId === s.id ? (
                    <RevisionForm submissionId={s.id} queue={queue} />
                  ) : (
                    <button className="revision-btn" onClick={() => openRevision(s.id)}>
                      🔄 На доработку
                    </button>
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
