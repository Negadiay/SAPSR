import { helpFor } from './helpContent';
import './help.css';

// Короткая справка по роли — снизу экрана; закрывается кнопкой или нажатием на фон.
export default function HelpSheet({ role, onClose }) {
  const { title, steps } = helpFor(role);
  return (
    <div className="dialog-overlay sheet-overlay" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <p className="dialog-title">{title}</p>
        <ol className="help-steps">
          {steps.map((text) => <li key={text}>{text}</li>)}
        </ol>
        <button type="button" className="btn btn-primary btn-block" onClick={onClose}>Понятно</button>
      </div>
    </div>
  );
}
