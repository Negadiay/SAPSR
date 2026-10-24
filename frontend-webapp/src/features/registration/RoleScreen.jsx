import Screen from '../../components/Screen';
import Icon from '../../components/Icon';

const ROLES = [
  ['student', 'student', 'Студент'],
  ['teacher', 'teacher', 'Преподаватель'],
];

export default function RoleScreen({ registration, registeredRole }) {
  const { regError, selectRole, loginExisting } = registration;

  return (
    <Screen>
      <p className="description">Выберите вашу роль в системе</p>
      <div className="role-container">
        {ROLES.map(([role, icon, label]) => (
          <div className="role-row" key={role}>
            <button className="role-card" onClick={() => selectRole(role)}>
              <span className="role-icon"><Icon name={icon} /></span>
              <span className="role-text">{label}</span>
            </button>
            {registeredRole === role && (
              <button className="role-login-btn" onClick={() => loginExisting(role)}>
                Войти
              </button>
            )}
          </div>
        ))}
        {/* Администратора не регистрируют — роль задаётся в базе (решение 11); после выхода — только вход. */}
        {registeredRole === 'admin' && (
          <button className="role-login-btn" onClick={() => loginExisting('admin')}>
            Войти как администратор
          </button>
        )}
      </div>
      {regError && <div className="reg-error role-error">{regError}</div>}
    </Screen>
  );
}
