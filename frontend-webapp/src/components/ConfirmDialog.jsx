// Подтверждение необратимого действия. danger — красная кнопка (удалить, отозвать), иначе — основная.
export default function ConfirmDialog({ title, body, confirmLabel, onConfirm, onCancel, danger = false }) {
  return (
    <div className="dialog-overlay" onClick={onCancel}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <p className="dialog-title">{title}</p>
        {body && <p className="dialog-body">{body}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-secondary" onClick={onCancel}>Отмена</button>
          <button type="button" className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
