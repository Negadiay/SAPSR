export default function RevisionForm({ submissionId, queue }) {
  const { revisionComment, setRevisionComment, suggestions, verdictLoading, submitVerdict, cancelRevision } = queue;

  return (
    <div className="revision-form">
      <textarea className="revision-input" rows={3}
        placeholder="Комментарий для студента..."
        value={revisionComment} onChange={(e) => setRevisionComment(e.target.value)} />
      <div className="comment-char-counter">
        {revisionComment.length} символов
      </div>
      {suggestions.length > 0 && (
        <div className="comment-suggestions">
          <div className="comment-suggestions-title">Шаблонные ответы</div>
          {suggestions.map((template) => (
            <button key={template.text} type="button" className="comment-suggestion"
              onClick={() => setRevisionComment(template.text)}>
              <span>{template.text}</span>
            </button>
          ))}
        </div>
      )}
      <div className="revision-btns">
        <button className="submit-btn" disabled={!revisionComment.trim() || verdictLoading}
          onClick={() => submitVerdict(submissionId, 'REVISION', revisionComment)}>
          {verdictLoading ? '⏳' : 'Отправить'}
        </button>
        <button className="secondary-btn" onClick={cancelRevision}>
          Отмена
        </button>
      </div>
    </div>
  );
}
