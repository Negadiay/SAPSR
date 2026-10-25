import AppLogo from './AppLogo';

// Экран входа и регистрации: знак приложения сверху, под ним содержимое.
export default function Screen({ children }) {
  return (
    <div className="screen-body entry">
      <div className="brand">
        <AppLogo size={56} />
        <p className="brand-name">SAPSR</p>
        <p className="brand-note">Проверка курсовых работ</p>
      </div>
      {children}
    </div>
  );
}
