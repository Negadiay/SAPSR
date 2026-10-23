import { useEffect } from 'react';
import { shortName } from '../../lib/names';

const FONT_SIZE_OPTIONS = ['small', 'normal', 'large', 'xlarge'];
const FONT_SIZE_LABELS = {
  small: 'Мелкий',
  normal: 'Обычный',
  large: 'Крупный',
  xlarge: 'Очень крупный',
};

const ROLE_LABELS = { STUDENT: 'Студент', TEACHER: 'Преподаватель', ADMIN: 'Администратор' };

// Кто вошёл: «Фамилия И. О.», у студента — ещё группа и курс. Администратора записывают в базу вручную —
// имени у него может не быть.
function ProfileCard({ profile }) {
  const name = shortName(profile);
  const isAdmin = profile?.role === 'ADMIN';
  if (!name && !isAdmin) return null;
  const isStudent = profile.role === 'STUDENT';
  return (
    <div className="settings-section settings-profile">
      <div className="settings-label">{ROLE_LABELS[profile.role] || 'Преподаватель'}</div>
      {name && <div className="settings-profile-name">{name}</div>}
      {isStudent && profile.group && (
        <div className="settings-desc">Группа {profile.group}{profile.course ? ` · ${profile.course} курс` : ''}</div>
      )}
      {isAdmin && <div className="settings-desc">Роль назначена в базе данных</div>}
    </div>
  );
}

export default function SettingsTab({ settings, onLogout, profile, onNeedProfile }) {
  const { theme, fontSize, contrast, toggleTheme, toggleContrast, setFontSize } = settings;

  // После регистрации данных ещё нет — запрашиваем их один раз при открытии настроек.
  useEffect(() => {
    if (!profile && onNeedProfile) onNeedProfile();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="tab-view">
      <h2 className="view-title">Настройки</h2>

      <ProfileCard profile={profile} />

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
