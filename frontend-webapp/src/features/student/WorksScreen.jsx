import AppHeader from '../../components/AppHeader';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import PrimaryAction from '../../components/PrimaryAction';
import { formatDate } from '../../lib/format';
import { groupVersions } from '../../lib/submissions';
import { workStatus } from '../../lib/status';
import TermInfo from './TermInfo';

// Главный экран студента: его работы и кнопка «Сдать работу».
export default function WorksScreen({ works, me, header, onOpen, onUpload }) {
  const { submissions, loaded } = works;
  const groups = groupVersions(submissions);

  return (
    <div className="screen-body">
      <AppHeader title="Мои работы" {...header} />
      <TermInfo me={me} />
      <PrimaryAction text="Сдать работу" onClick={onUpload} />

      {loaded && groups.length === 0 && (
        <div className="empty" data-tour="works-list">
          <Icon name="file" className="empty-icon" />
          <p>Работ пока нет.</p>
          <p className="hint">Сдайте первую — итог проверки придёт в чат через несколько минут.</p>
        </div>
      )}

      <ul className="card-list" data-tour="works-list">
        {groups.map(({ key, latest, items }) => {
          const status = workStatus(latest);
          return (
            <li key={key}>
              <button type="button" className="card card-link" onClick={() => onOpen(latest.id)}>
                <span className="card-main">
                  <span className="card-title">{latest.file_name}</span>
                  <StatusLine tone={status.tone}>{status.label}</StatusLine>
                  <span className="card-meta">
                    {items.length > 1 ? `Версия ${items.length} · ` : ''}{formatDate(latest.created_at, { short: true })}
                  </span>
                </span>
                <Icon name="chevronRight" className="card-chevron" />
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
