import Screen from '../../components/Screen';
import { formatCountdown } from '../../lib/format';

export default function ConfirmCodeScreen({ registration }) {
  const {
    teacherEmail, regCode, setRegCode, codeTimeLeft, regError,
    registering, sendingCode, confirmCode, resendCode, backToEmail,
  } = registration;

  return (
    <Screen>
      <p className="description">Код отправлен на<br /><b>{teacherEmail}</b></p>
      <p className="reg-hint">Проверьте папку «Спам», если письмо не пришло</p>
      <form onSubmit={confirmCode} className="register-form">
        <input type="text" className="reg-input code-input-wide" placeholder="6-значный код"
          value={regCode} onChange={(e) => setRegCode(e.target.value)} maxLength={6} inputMode="numeric" autoFocus />
        <p className={`code-timer ${codeTimeLeft <= 0 ? 'code-timer-expired' : ''}`}>
          {codeTimeLeft > 0
            ? `Код действителен ещё ${formatCountdown(codeTimeLeft)}`
            : 'Срок действия кода истёк'}
        </p>
        {regError && <div className="reg-error">{regError}</div>}
        <div className="vertical-button-group">
          <button type="submit" className="submit-btn" disabled={registering || codeTimeLeft <= 0}>
            {registering ? (
              <>
                <span className="btn-spinner" aria-hidden="true" />
                Проверка...
              </>
            ) : 'Подтвердить'}
          </button>
          <button type="button" className="secondary-btn" disabled={sendingCode} onClick={resendCode}>
            {sendingCode ? (
              <>
                <span className="btn-spinner btn-spinner-secondary" aria-hidden="true" />
                Отправка...
              </>
            ) : 'Отправить код повторно'}
          </button>
          <button type="button" className="secondary-btn" onClick={backToEmail}>
            Назад
          </button>
        </div>
      </form>
    </Screen>
  );
}
