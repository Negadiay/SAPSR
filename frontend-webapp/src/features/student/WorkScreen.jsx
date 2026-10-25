import { useState } from 'react';
import AppHeader from '../../components/AppHeader';
import CheckSteps from '../../components/CheckSteps';
import ConfirmDialog from '../../components/ConfirmDialog';
import FindingList from '../../components/FindingList';
import Icon from '../../components/Icon';
import PrimaryAction from '../../components/PrimaryAction';
import StatusLine from '../../components/StatusLine';
import { downloadReport } from '../../api/submissions';
import { formatDate } from '../../lib/format';
import { chainOf } from '../../lib/submissions';
import { contentStep, formatStep, teacherStep, workStatus } from '../../lib/status';
import { workTypeLabel } from '../../lib/workTypes';
import TeacherComments from './TeacherComments';

// Экран одной работы: ход проверки, замечания, комментарии преподавателя, версии и действия.
export default function WorkScreen({ id, works, header, onSelectVersion, onUploadNew, onWithdrawn }) {
  const { submissions, loaded, withdraw, withdrawing, error } = works;
  const [confirming, setConfirming] = useState(false);
  const s = submissions.find((x) => x.id === id);

  if (!s) {
    return (
      <div className="screen-body">
        <AppHeader title="Работа" {...header} />
        <p className="empty">{loaded ? 'Работа не найдена. Возможно, её отозвали.' : 'Загрузка…'}</p>
      </div>
    );
  }

  const chain = chainOf(submissions, id);
  const versions = chain ? chain.items : [s];
  const number = versions.length - versions.findIndex((x) => x.id === id);
  const isLatest = versions[0].id === id;
  const canResubmit = isLatest
    && (s.status === 'REJECTED' || s.status === 'FAILED' || s.teacher_verdict === 'REVISION');
  const canWithdraw = s.status === 'SUCCESS' && !s.teacher_verdict;
  const hasReport = s.status === 'SUCCESS' || s.status === 'REJECTED';
  const commentedEarlier = isLatest && !s.has_review_file && versions.slice(1).find((v) => v.has_review_file);

  const meta = [workTypeLabel(s.work_type), versions.length > 1 ? `версия ${number}` : '', formatDate(s.created_at)];

  return (
    <div className="screen-body">
      <AppHeader title="Работа" {...header} />
      <div className="work-head">
        <p className="work-file">{s.file_name}</p>
        <p className="hint">{meta.filter(Boolean).join(' · ')}</p>
        {s.teacher_name && <p className="hint">Преподаватель: {s.teacher_name}</p>}
      </div>

      <CheckSteps steps={[
        { title: 'Оформление', ...formatStep(s) },
        { title: 'Содержание', ...contentStep(s) },
        { title: 'Преподаватель', ...teacherStep(s) },
      ]} />

      {canResubmit && (
        <PrimaryAction text="Загрузить новую версию"
          onClick={() => onUploadNew({ teacherId: s.teacher_id, workType: s.work_type })} />
      )}

      {s.teacher_comment && <blockquote className="teacher-note">{s.teacher_comment}</blockquote>}
      {s.has_review_file && <TeacherComments submission={s} />}
      {commentedEarlier && (
        <button type="button" className="notice" onClick={() => onSelectVersion(commentedEarlier.id)}>
          <Icon name="comment" />
          <span>Комментарии преподавателя — в версии {versions.length - versions.indexOf(commentedEarlier)}</span>
          <Icon name="chevronRight" />
        </button>
      )}

      <FindingList raw={s.format_errors} />

      {(hasReport || canWithdraw) && (
        <div className="actions">
          {hasReport && (
            <button type="button" className="btn btn-secondary" onClick={() => downloadReport(s.id)}>
              <Icon name="report" /><span>Отчёт PDF</span>
            </button>
          )}
          {canWithdraw && (
            <button type="button" className="btn btn-ghost-danger" disabled={withdrawing === s.id}
              onClick={() => setConfirming(true)}>
              <Icon name={withdrawing === s.id ? 'loading' : 'withdraw'} /><span>Отозвать</span>
            </button>
          )}
        </div>
      )}
      {error && <StatusLine tone="error">{error}</StatusLine>}

      {versions.length > 1 && (
        <section className="block">
          <h3 className="section-title">Версии</h3>
          <ul className="version-list">
            {versions.map((v, i) => {
              const status = workStatus(v);
              const current = v.id === id;
              return (
                <li key={v.id}>
                  <button type="button" className={`version ${current ? 'version-current' : ''}`}
                    aria-current={current ? 'true' : undefined} onClick={() => !current && onSelectVersion(v.id)}>
                    <span className="version-name">Версия {versions.length - i}</span>
                    <span className="hint">{formatDate(v.created_at, { short: true })}</span>
                    <StatusLine tone={status.tone}>{status.label}</StatusLine>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {confirming && (
        <ConfirmDialog title="Отозвать работу?" body="Преподаватель её больше не увидит." confirmLabel="Отозвать" danger
          onCancel={() => setConfirming(false)}
          onConfirm={async () => { setConfirming(false); if (await withdraw(s.id)) onWithdrawn(); }} />
      )}
    </div>
  );
}
