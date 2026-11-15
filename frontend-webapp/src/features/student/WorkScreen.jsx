import { useState } from 'react';
import AppHeader from '../../components/AppHeader';
import CheckSteps from '../../components/CheckSteps';
import ConfirmDialog from '../../components/ConfirmDialog';
import ReportView from '../../components/ReportView';
import Icon from '../../components/Icon';
import PrimaryAction from '../../components/PrimaryAction';
import StatusLine from '../../components/StatusLine';
import { downloadCommented, downloadPdfVersion, downloadReport, downloadReviewFile } from '../../api/submissions';
import { count, formatDate } from '../../lib/format';
import { chainOf } from '../../lib/submissions';
import { contentStep, formatStep, teacherStep, workStatus } from '../../lib/status';
import { workTypeLabel } from '../../lib/workTypes';
import TeacherComments from './TeacherComments';

// Итог преподавателя — первым на экране (решение 15): вердикт, число комментариев, файл с пометками
// и, если работу вернули, кнопка новой версии.
function TeacherVerdict({ submission, canResubmit, onUploadNew }) {
  const approved = submission.teacher_verdict === 'APPROVED';
  const comments = submission.review_annotations || 0;
  return (
    <section className={`teacher-verdict teacher-verdict-${approved ? 'ok' : 'revision'}`} aria-label="Решение преподавателя">
      <h3 className="teacher-verdict-title">
        <Icon name={approved ? 'ok' : 'revision'} />
        <span>Преподаватель: {approved ? 'принято' : 'на доработку'}</span>
      </h3>
      {comments > 0 && (
        <p className="hint">{count(comments, 'комментарий', 'комментария', 'комментариев')} в файле с пометками</p>
      )}
      {submission.teacher_comment && <blockquote className="teacher-note">{submission.teacher_comment}</blockquote>}
      {submission.has_review_file && (
        <button type="button" className="btn btn-secondary btn-block" onClick={() => downloadReviewFile(submission.id)}>
          <Icon name="download" /><span>Скачать файл с пометками</span>
        </button>
      )}
      {canResubmit && (
        <PrimaryAction text="Загрузить новую версию"
          onClick={() => onUploadNew({ teacherId: submission.teacher_id })} />
      )}
    </section>
  );
}

// Экран одной работы: итог преподавателя и его комментарии, ход проверки, замечания, версии и действия.
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

      {s.source_format === 'docx' && (
        <div className="docx-note">
          <p className="hint">Проверена PDF-версия: номера страниц — по ней.</p>
          <button type="button" className="link-btn" onClick={() => downloadPdfVersion(s.id)}>
            <Icon name="download" />Скачать PDF-версию
          </button>
        </div>
      )}

      {s.teacher_verdict && (
        <TeacherVerdict submission={s} canResubmit={canResubmit} onUploadNew={onUploadNew} />
      )}
      {s.teacher_verdict && s.has_review_file && <TeacherComments submission={s} download={false} />}

      <CheckSteps steps={[
        { title: 'Оформление', ...formatStep(s) },
        { title: 'Содержание', ...contentStep(s) },
        { title: 'Преподаватель', ...teacherStep(s) },
      ]} />

      {canResubmit && !s.teacher_verdict && (
        <PrimaryAction text="Загрузить новую версию"
          onClick={() => onUploadNew({ teacherId: s.teacher_id })} />
      )}
      {commentedEarlier && (
        <button type="button" className="notice" onClick={() => onSelectVersion(commentedEarlier.id)}>
          <Icon name="comment" />
          <span>Комментарии преподавателя — в версии {versions.length - versions.indexOf(commentedEarlier)}</span>
          <Icon name="chevronRight" />
        </button>
      )}

      <ReportView submission={s} />

      {(hasReport || canWithdraw) && (
        <div className="actions">
          {hasReport && (
            <>
              <button type="button" className="btn btn-secondary" onClick={() => downloadCommented(s.id)}>
                <Icon name="comment" /><span>С комментариями</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={() => downloadReport(s.id)}>
                <Icon name="report" /><span>Отчёт PDF</span>
              </button>
            </>
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
