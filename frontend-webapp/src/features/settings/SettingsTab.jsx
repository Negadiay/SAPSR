const FONT_SIZE_OPTIONS = ['small', 'normal', 'large', 'xlarge'];
const FONT_SIZE_LABELS = {
  small: 'Мелкий',
  normal: 'Обычный',
  large: 'Крупный',
  xlarge: 'Очень крупный',
};

export default function SettingsTab({ settings, onLogout }) {
  const { theme, fontSize, contrast, toggleTheme, toggleContrast, setFontSize } = settings;

  return (
    <div className="tab-view">
      <h2 className="view-title">Настройки</h2>

      <div className="settings-section">
        <div className="settings-label">Тема оформления</div>
        <div className="settings-row">
          <span className="settings-desc">Ночной режим</span>
          <button className={`toggle-switch ${theme === 'dark' ? 'on' : ''}`}
            onClick={toggleTheme}
            aria-label="Переключить ночной режим">
            <span className="toggle-thumb" />
          </button>
        </div>
        <div className="settings-row settings-row-spaced">
          <span className="settings-desc">Контрастный режим</span>
          <button className={`toggle-switch ${contrast ? 'on' : ''}`}
            onClick={toggleContrast}
            aria-label="Переключить контрастный режим">
            <span className="toggle-thumb" />
          </button>
        </div>
      </div>

      <div className="settings-section">
        <div className="settings-label">Размер шрифта</div>
        <div className="fontsize-slider-row">
          <span className="fontsize-marker">A−</span>
          <input
            className="fontsize-slider"
            type="range"
            min="0"
            max={FONT_SIZE_OPTIONS.length - 1}
            step="1"
            value={Math.max(0, FONT_SIZE_OPTIONS.indexOf(fontSize))}
            onChange={(e) => setFontSize(FONT_SIZE_OPTIONS[Number(e.target.value)] || 'normal')}
            aria-label="Размер шрифта"
          />
          <span className="fontsize-marker fontsize-marker-large">A+</span>
        </div>
        <div className="settings-desc">{FONT_SIZE_LABELS[fontSize] || FONT_SIZE_LABELS.normal}</div>
      </div>

      <div className="settings-divider" />
      <button className="secondary-btn settings-wide-btn" onClick={onLogout}>Выйти из системы</button>
    </div>
  );
}
