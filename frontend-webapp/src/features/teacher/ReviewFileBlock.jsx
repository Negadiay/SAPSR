import AnnotationList from '../../components/AnnotationList';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import { count } from '../../lib/format';

// Без комментариев в файле на доработку не отправить, а чужой файл — явная ошибка: об этом говорим коротко.
const WARNINGS = {
  NO_ANNOTATIONS: 'Комментариев нет — сохраните файл не через «Печать в PDF».',
  DIFFERENT_DOCUMENT: 'Похоже, это файл другой работы.',
};

// Возврат работы с пометками: преподаватель скачивает PDF, комментирует в своей программе и загружает обратно.
export default function ReviewFileBlock({ submission, queue }) {
  const review = queue.reviews[submission.id];
  const inputId = `review-file-${submission.id}`;
  const attached = Boolean(review?.review_file_id || (!review && submission.has_review_file));
  const comments = queue.annotationCount(submission);
  const warnings = (review?.warnings || []).filter((w) => WARNINGS[w.code]);

  return (
    <section className="block">
      <h3 className="section-title">Файл с пометками</h3>
      <details className="help-details">
        <summary>Как подготовить</summary>
        <p>Скачайте работу, добавьте комментарии в Acrobat Reader или Edge, сохраните файл
          (не через «Печать в PDF») и загрузите сюда. Студент увидит комментарии в приложении и в чате.</p>
      </details>
      <label htmlFor={inputId} className="btn btn-secondary btn-block">
        {review?.uploading
          ? <><Icon name="loading" /><span>Загрузка…</span></>
          : <><Icon name="upload" /><span>{attached ? 'Заменить файл' : 'Загрузить файл'}</span></>}
      </label>
      <input id={inputId} className="visually-hidden" type="file" accept=".pdf,application/pdf"
        onChange={(e) => { queue.attachReviewFile(submission.id, e.target.files[0]); e.target.value = ''; }} />

      {review?.error && <StatusLine tone="error">{review.error}</StatusLine>}
      {attached && (
        <StatusLine tone={comments > 0 ? 'ok' : 'warning'}>
          Загружено: {count(comments, 'комментарий', 'комментария', 'комментариев')}
        </StatusLine>
      )}
      {warnings.map((w) => <StatusLine key={w.code} tone="warning">{WARNINGS[w.code]}</StatusLine>)}
      {review?.annotations?.length > 0 && <AnnotationList annotations={review.annotations} />}
    </section>
  );
}
