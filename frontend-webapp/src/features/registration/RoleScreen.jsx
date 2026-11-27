import Screen from '../../components/Screen';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';

const ROLES = [
  { role: 'student', label: 'Студент', note: 'Сдать работу на проверку' },
  { role: 'teacher', label: 'Преподаватель', note: 'Проверять работы студентов' },
];

export default function RoleScreen({ registration, registeredRole }) {
  const { regError, selectRole, loginExisting } = registration;

  return (
    <Screen>
      <h2 className="screen-lead">Выберите роль</h2>
      <ul className="card-list">
        {ROLES.map(({ role, label, note }) => (
          <li key={role} className="role-row">
            <button type="button" className="card card-link role-card" onClick={() => selectRole(role)}>
              <Icon name={role} className="role-icon" />
              <span className="card-main">
                <span className="card-title">{label}</span>
                <span className="card-subtitle">{note}</span>
              </span>
              <Icon name="chevronRight" className="card-chevron" />
            </button>
            {registeredRole === role && (
              <button type="button" className="btn btn-primary" onClick={() => loginExisting(role)}>Войти</button>
            )}
          </li>
        ))}
      </ul>
      {/* Администратора не регистрируют — роль задаётся в базе (решение 11); после смены роли — только вход. */}
      {registeredRole === 'admin' && (
        <button type="button" className="btn btn-primary btn-block" onClick={() => loginExisting('admin')}>
          Войти как администратор
        </button>
      )}
      {regError && <StatusLine tone="error" className="form-status">{regError}</StatusLine>}
    </Screen>
  );
}
