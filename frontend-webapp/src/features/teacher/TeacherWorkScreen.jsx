import { useState } from 'react';
import AppHeader from '../../components/AppHeader';
import ConfirmDialog from '../../components/ConfirmDialog';
import FindingList from '../../components/FindingList';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import { downloadReport } from '../../api/submissions';
import { downloadSubmissionPdf } from '../../api/teacher';
import { formatDate } from '../../lib/format';
import { autoCheckForTeacher } from '../../lib/status';
import TeacherComments from '../student/TeacherComments';
import ReviewFileBlock from './ReviewFileBlock';

const VERDICT = {
  APPROVED: { tone: 'ok', label: 'Вы приняли работу' },
  REVISION: { tone: 'warning', label: 'Вы вернули работу на доработку' },
};

const CONFIRM = {
  APPROVED: { title: 'Принять работу?', body: 'Студент получит уведомление. Изменить решение будет нельзя.', label: 'Принять' },
  REVISION: { title: 'Вернуть на доработку?', body: 'Студент получит комментарии из файла с пометками.', label: 'Вернуть' },
};

// Экран работы у преподавателя: итог автоматической проверки, замечания, файл с пометками и решение.
export default function TeacherWorkScreen({ id, teacher, header, onDecided }) {
  const { submissions, queue } = teacher;
  const [confirm, setConfirm] = useState(null);
  const pending = submissions.pending.find((s) => s.id === id);
  const s = pending || submissions.history.find((x) => x.id === id);

  if (!s) {
    return (
      <div className="screen-body">
        <AppHeader title="Работа" {...header} />
        <p className="empty">{submissions.loaded ? 'Работа не найдена. Возможно, студент её отозвал.' : 'Загрузка…'}</p>
      </div>
    );
  }

  const decided = VERDICT[s.teacher_verdict];
  const check = autoCheckForTeacher(s);
  const canRevise = queue.canSendRevision(s);
  const received = `Получена ${formatDate(s.created_at)}`;
  const firstVersion = s.version > 1 && s.first_submitted_at
    ? `Версия ${s.version}, первая — ${formatDate(s.first_submitted_at, { short: true })}` : '';

  const decide = async (verdict) => {
    setConfirm(null);
    if (await queue.decide(s.id, verdict)) onDecided();
  };

  return (
    <div className="screen-body">
      <AppHeader title="Работа" {...header} />
      <div className="work-head">
        <p className="work-file">{s.student_name || 'Студент'}{s.student_group ? ` · ${s.student_group}` : ''}</p>
        <p className="hint">{s.file_name}</p>
        <p className="hint">{received}</p>
        {firstVersion && <p className="hint">{firstVersion}</p>}
      </div>

      {decided ? (
        <StatusLine tone={decided.tone}>{decided.label}</StatusLine>
      ) : (
        <div className="block">
          <StatusLine tone={check.format.tone}>{check.format.text}</StatusLine>
          {check.content && <StatusLine tone={check.content.tone}>{check.content.text}</StatusLine>}
        </div>
      )}

      <div className="actions">
        <button type="button" className="btn btn-secondary" onClick={() => downloadSubmissionPdf(s.id)}>
          <Icon name="download" /><span>Скачать работу</span>
        </button>
        {!decided && (
          <button type="button" className="btn btn-secondary" onClick={() => downloadReport(s.id)}>
            <Icon name="report" /><span>Отчёт PDF</span>
          </button>
        )}
      </div>

      {decided && s.teacher_comment && <blockquote className="teacher-note">{s.teacher_comment}</blockquote>}
      {decided && s.has_review_file && <TeacherComments submission={s} />}
      {!decided && <FindingList raw={s.format_errors} forTeacher />}
      {!decided && <ReviewFileBlock submission={s} queue={queue} />}

      {!decided && (
        <section className="block verdict">
          <h3 className="section-title">Решение</h3>
          <div className="verdict-buttons">
            <button type="button" className="btn btn-ok" disabled={queue.deciding} onClick={() => setConfirm('APPROVED')}>
              <Icon name="ok" /><span>Принять</span>
            </button>
            <button type="button" className="btn btn-warning" disabled={queue.deciding || !canRevise}
              onClick={() => setConfirm('REVISION')}>
              <Icon name="revision" /><span>На доработку</span>
            </button>
          </div>
          {!canRevise && <p className="hint">Для доработки загрузите файл с пометками.</p>}
          {queue.verdictError && <StatusLine tone="error">{queue.verdictError}</StatusLine>}
        </section>
      )}

      {confirm && (
        <ConfirmDialog title={CONFIRM[confirm].title} body={CONFIRM[confirm].body} confirmLabel={CONFIRM[confirm].label}
          onCancel={() => setConfirm(null)} onConfirm={() => decide(confirm)} />
      )}
    </div>
  );
}
