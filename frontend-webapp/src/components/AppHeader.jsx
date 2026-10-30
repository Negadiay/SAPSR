import Icon from './Icon';

/**
 * Заголовок экрана: стрелка «Назад» (если нет родной кнопки Telegram), название и кнопки-иконки справа.
 * actions — [{ icon, label, onClick }]; у каждой кнопки есть подпись для программ чтения с экрана.
 */
export default function AppHeader({ title, onBack, actions = [] }) {
  return (
    <header className="app-header">
      {onBack && (
        <button type="button" className="icon-btn header-back" onClick={onBack}>
          <Icon name="back" label="Назад" />
        </button>
      )}
      <h1 className="app-title">{title}</h1>
      {actions.length > 0 && (
        <div className="header-actions">
          {actions.map(({ icon, label, onClick }) => (
            <button key={icon} type="button" className="icon-btn" title={label} onClick={onClick}
              data-tour={`header-${icon}`}>
              <Icon name={icon} label={label} />
            </button>
          ))}
        </div>
      )}
    </header>
  );
}
