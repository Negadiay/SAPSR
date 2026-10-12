import Screen from '../../components/Screen';

export default function StudentRegisterScreen({ registration }) {
  const { regInput, changeRegInput, regError, registering, submitStudent, backToRole } = registration;

  return (
    <Screen>
      <p className="description">Введите ФИО и номер группы</p>
      <form onSubmit={submitStudent} className="register-form">
        <input type="text" className="reg-input" placeholder="Иванов И.О., 321702"
          value={regInput} onChange={(e) => changeRegInput(e.target.value)} autoFocus />
        <p className="reg-hint">Формат: Иванов И.И., 321702. Только кириллица, точки и запятая.</p>
        {regError && <div className="reg-error">{regError}</div>}
        <div className="vertical-button-group">
          <button type="submit" className="submit-btn" disabled={registering}>
            {registering ? 'Регистрация...' : 'Зарегистрироваться'}
          </button>
          <button type="button" className="secondary-btn" onClick={backToRole}>Назад</button>
        </div>
      </form>
    </Screen>
  );
}
