import Screen from '../../components/Screen';
import PrimaryAction from '../../components/PrimaryAction';
import StatusLine from '../../components/StatusLine';
import { useBackButton } from '../../telegram/useTelegramButtons';

const DOMAIN = '@bsuir.by';

export default function TeacherRegisterScreen({ registration }) {
  const { teacherEmail, setTeacherEmail, regError, sendingCode, sendCode, backToRole } = registration;
  const nativeBack = useBackButton(sendingCode ? null : backToRole);
  const email = teacherEmail.trim();
  const suggestDomain = email && !email.includes('@');

  if (sendingCode) {
    return (
      <Screen>
        <div className="waiting" role="status">
          <span className="spinner" aria-hidden="true" />
          <p>Ищем вас в ИИС БГУИР.</p>
          <p className="hint">Это может занять пару минут.</p>
        </div>
      </Screen>
    );
  }

  return (
    <Screen>
      <h2 className="screen-lead">Вход для преподавателя</h2>
      <form onSubmit={sendCode} className="form">
        <div className="field">
          <label className="field-label" htmlFor="teacher-email">Рабочая почта</label>
          <input id="teacher-email" type="email" className="input" placeholder={`ivanov${DOMAIN}`} autoComplete="email"
            value={teacherEmail} onChange={(e) => setTeacherEmail(e.target.value)} />
          {suggestDomain ? (
            <button type="button" className="link-btn" onClick={() => setTeacherEmail(email + DOMAIN)}>
              Дописать {DOMAIN}
            </button>
          ) : (
            <p className="hint">ФИО возьмём из ИИС БГУИР. На почту придёт код.</p>
          )}
        </div>
        {regError && <StatusLine tone="error">{regError}</StatusLine>}
        <PrimaryAction text="Получить код" submit onClick={sendCode} disabled={!email} />
        {!nativeBack && <button type="button" className="btn btn-secondary btn-block" onClick={backToRole}>Назад</button>}
      </form>
    </Screen>
  );
}
