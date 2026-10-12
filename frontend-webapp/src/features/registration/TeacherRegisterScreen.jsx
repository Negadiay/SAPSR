import Screen from '../../components/Screen';

export default function TeacherRegisterScreen({ registration }) {
  const { teacherEmail, setTeacherEmail, regError, sendingCode, sendCode, backToRole } = registration;

  return (
    <Screen>
      {sendingCode ? (
        <div className="code-sending-wait">
          <div className="code-sending-spinner" />
          <p className="code-sending-text">Пожалуйста, подождите.<br />Это может занять несколько минут.</p>
        </div>
      ) : (
        <>
          <p className="description">Регистрация преподавателя</p>
          <form onSubmit={sendCode} className="register-form">
            <input type="email" className="reg-input" placeholder="ivanov@bsuir.by"
              value={teacherEmail} onChange={(e) => setTeacherEmail(e.target.value)} autoFocus />
            <p className="reg-hint">Введите корпоративную почту @bsuir.by. ФИО будет заполнено автоматически из IIS.</p>
            {regError && <div className="reg-error">{regError}</div>}
            <div className="vertical-button-group">
              <button type="submit" className="submit-btn">Отправить код</button>
              <button type="button" className="secondary-btn" onClick={backToRole}>Назад</button>
            </div>
          </form>
        </>
      )}
    </Screen>
  );
}
