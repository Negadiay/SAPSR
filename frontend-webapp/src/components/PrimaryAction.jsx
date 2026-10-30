import { useMainButton } from '../telegram/useTelegramButtons';
import Icon from './Icon';

/**
 * Основное действие экрана. В Telegram — родная кнопка внизу экрана (MainButton), иначе — своя кнопка на этом месте.
 * В форме своя кнопка отправляет форму (type="submit"), поэтому onClick для неё не нужен.
 */
export default function PrimaryAction({ text, onClick, disabled = false, progress = false, progressText, submit = false }) {
  const native = useMainButton({ text, onClick, disabled, progress });
  if (native) return null;
  return (
    <button type={submit ? 'submit' : 'button'} className="btn btn-primary btn-block" data-tour="primary"
      disabled={disabled || progress} onClick={submit ? undefined : onClick}>
      {progress ? <><Icon name="loading" /><span>{progressText || text}</span></> : text}
    </button>
  );
}
