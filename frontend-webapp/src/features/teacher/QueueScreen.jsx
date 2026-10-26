import AppHeader from '../../components/AppHeader';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import { formatDate } from '../../lib/format';
import { autoCheckForTeacher } from '../../lib/status';

const VERDICT = {
  APPROVED: { tone: 'ok', label: 'Принята' },
  REVISION: { tone: 'warning', label: 'На доработке' },
};

function WorkCard({ s, onOpen }) {
  const decided = VERDICT[s.teacher_verdict];
  const check = decided ? null : autoCheckForTeacher(s);
  const meta = [formatDate(s.created_at, { short: true }), s.version > 1 ? `версия ${s.version}` : ''];
  return (
    <button type="button" className="card card-link" onClick={() => onOpen(s.id)}>
      <span className="card-main">
        <span className="card-title">
          {s.student_name || 'Студент'}
          {s.student_group && <span className="card-title-extra"> · {s.student_group}</span>}
        </span>
        <span className="card-subtitle">{s.file_name}</span>
        {decided && <StatusLine tone={decided.tone}>{decided.label}</StatusLine>}
        {check && <StatusLine tone={check.format.tone}>{check.format.text}</StatusLine>}
        {check?.content && <StatusLine tone={check.content.tone}>{check.content.text}</StatusLine>}
        <span className="card-meta">{meta.filter(Boolean).join(' · ')}</span>
      </span>
      <Icon name="chevronRight" className="card-chevron" />
    </button>
  );
}

// Главный экран преподавателя: работы на проверке и проверенные, поиск и фильтр по группе.
export default function QueueScreen({ teacher, header, onOpen }) {
  const { submissions, queue } = teacher;
  const { segment, setSegment, search, setSearch, group, setGroup, groups, filtered } = queue;
  const list = segment === 'pending' ? submissions.pending : submissions.history;

  return (
    <div className="screen-body">
      <AppHeader title="Работы" {...header} />

      <div className="segmented" role="tablist" aria-label="Работы" data-tour="queue-tabs">
        {[['pending', 'На проверке', submissions.pending.length], ['history', 'Проверено', null]].map(([id, label, n]) => (
          <button key={id} type="button" role="tab" aria-selected={segment === id}
            className={`segment ${segment === id ? 'segment-active' : ''}`} onClick={() => setSegment(id)}>
            {label}{n ? <span className="segment-count">{n}</span> : null}
          </button>
        ))}
      </div>

      <input className="input" type="search" placeholder="Фамилия или группа" aria-label="Поиск" data-tour="queue-search"
        value={search} onChange={(e) => setSearch(e.target.value)} />

      {groups.length > 1 && (
        <div className="chips" role="group" aria-label="Группа">
          {['', ...groups].map((g) => (
            <button key={g || 'all'} type="button" aria-pressed={group === g}
              className={`chip ${group === g ? 'chip-active' : ''}`} onClick={() => setGroup(g)}>
              {g || 'Все'}
            </button>
          ))}
        </div>
      )}

      {submissions.loaded && list.length === 0 && (
        <div className="empty" data-tour="queue-list">
          <Icon name={segment === 'pending' ? 'queue' : 'history'} className="empty-icon" />
          <p>{segment === 'pending' ? 'Новых работ нет.' : 'Проверенных работ нет.'}</p>
          {segment === 'pending' && <p className="hint">О новой работе сообщим в чат.</p>}
        </div>
      )}
      {list.length > 0 && filtered.length === 0 && <p className="empty">Ничего не найдено.</p>}

      <ul className="card-list" data-tour="queue-list">
        {filtered.map((s) => <li key={s.id}><WorkCard s={s} onOpen={onOpen} /></li>)}
      </ul>
    </div>
  );
}
