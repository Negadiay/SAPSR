export default function ConfirmDialog({ title, body, confirmLabel, onConfirm, onCancel }) {
  return (
    <div className="confirm-overlay" onClick={onCancel}>
      <div className="confirm-dialog" onClick={e => e.stopPropagation()}>
        <p className="confirm-title">{title}</p>
        <p className="confirm-body">{body}</p>
        <div className="confirm-btns">
          <button className="secondary-btn" onClick={onCancel}>Отмена</button>
          <button className="withdraw-confirm-btn" onClick={onConfirm}>{confirmLabel}</button>
        </div>
      </div>
    </div>
  );
}
