// Без комментариев в файле на доработку не отправить, а чужой файл — явная ошибка: об этом говорим коротко.
const WARNINGS = {
  NO_ANNOTATIONS: 'В документе нет комментариев. Сохраните его обычным сохранением, а не печатью в PDF.',
  DIFFERENT_DOCUMENT: 'Похоже, это другой документ, а не работа этого студента.',
};

// Возврат работы с пометками: преподаватель скачивает PDF, комментирует в своей программе и загружает обратно.
// Показываем только итог загрузки; что прочиталось из файла, студент увидит в сообщении и в приложении.
export default function ReviewFileBlock({ submission, queue }) {
  const review = queue.reviews[submission.id];
  const inputId = `review-file-${submission.id}`;
  const attached = Boolean(review?.review_file_id || (!review && submission.has_review_file));
  const warnings = (review?.warnings || []).filter((w) => WARNINGS[w.code]);

  return (
    <div className="ts-review">
      <div className="ts-review-title">📎 Работа с вашими пометками</div>
      <details className="ts-review-help">
        <summary>Как сделать</summary>
        Скачайте работу, откройте в Acrobat Reader, Edge или другой программе с комментариями, выделяйте текст
        и добавляйте заметки. Сохраните файл обычным сохранением, не через печать, и загрузите сюда.
      </details>
      <label htmlFor={inputId} className="download-btn-sm ts-review-upload">
        {review?.uploading ? '⏳ Загрузка…' : (attached ? 'Заменить документ' : 'Загрузить документ')}
      </label>
      <input id={inputId} type="file" accept=".pdf,application/pdf" hidden
        onChange={(e) => { queue.attachReviewFile(submission.id, e.target.files[0]); e.target.value = ''; }} />

      {review?.error && <div className="ts-review-error">❌ {review.error}</div>}
      {attached && <div className="ts-review-ok">✅ Документ успешно загружен</div>}
      {warnings.map((w) => (
        <div key={w.code} className="ts-review-warning">⚠️ {WARNINGS[w.code]}</div>
      ))}
    </div>
  );
}
