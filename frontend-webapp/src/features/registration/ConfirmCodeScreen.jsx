import Screen from '../../components/Screen';
import PrimaryAction from '../../components/PrimaryAction';
import StatusLine from '../../components/StatusLine';
import { useBackButton } from '../../telegram/useTelegramButtons';
import { formatCountdown } from '../../lib/format';

export default function ConfirmCodeScreen({ registration }) {
  const {
    teacherEmail, regCode, setRegCode, codeTimeLeft, regError,
    registering, sendingCode, confirmCode, resendCode, backToEmail,
  } = registration;
  const nativeBack = useBackButton(backToEmail);
  const expired = codeTimeLeft <= 0;

  return (
    <Screen>
      <h2 className="screen-lead">Код из письма</h2>
      <p className="screen-note">Отправили на <b>{teacherEmail}</b>. Нет письма — проверьте «Спам».</p>
      <form onSubmit={confirmCode} className="form">
        <input type="text" className="input code-input" placeholder="000000" aria-label="Код из письма"
          value={regCode} onChange={(e) => setRegCode(e.target.value.replace(/\D/g, ''))} maxLength={6}
          inputMode="numeric" autoComplete="one-time-code" autoFocus />
        <p className={expired ? 'field-error' : 'hint'}>
          {expired ? 'Код устарел — запросите новый.' : `Код действует ${formatCountdown(codeTimeLeft)}`}
        </p>
        {regError && <StatusLine tone="error">{regError}</StatusLine>}
        <PrimaryAction text="Подтвердить" progressText="Проверка…" submit onClick={confirmCode}
          disabled={expired || regCode.length < 6} progress={registering} />
        <div className="actions">
          <button type="button" className="btn btn-secondary" disabled={sendingCode} onClick={resendCode}>
            {sendingCode ? 'Отправка…' : 'Новый код'}
          </button>
          {!nativeBack && <button type="button" className="btn btn-secondary" onClick={backToEmail}>Другая почта</button>}
        </div>
      </form>
    </Screen>
  );
}
