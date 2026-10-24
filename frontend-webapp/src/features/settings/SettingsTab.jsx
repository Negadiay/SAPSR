import { useEffect, useState } from 'react';
import { shortName } from '../../lib/names';
import Icon from '../../components/Icon';
import ThemeEditor from './ThemeEditor';
import { readabilityIssues } from './customTheme';

const FONT_SIZE_OPTIONS = ['small', 'normal', 'large', 'xlarge'];
const FONT_SIZE_LABELS = {
  small: 'Мелкий',
  normal: 'Обычный',
  large: 'Крупный',
  xlarge: 'Очень крупный',
};

const ROLE_LABELS = { STUDENT: 'Студент', TEACHER: 'Преподаватель', ADMIN: 'Администратор' };

// Кто вошёл: «Фамилия И. О.», у студента — ещё группа и курс. У администратора, записанного в базу вручную,
// имени может не быть — тогда только роль.
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
    </div>
  );
}

// Переключатель в строке настроек. off — режим сейчас не действует (своя тема заменяет ночной и контрастный).
function Toggle({ label, name, on, off = false, spaced = true, onClick }) {
  return (
    <div className={`settings-row ${spaced ? 'settings-row-spaced' : ''} ${off ? 'settings-row-off' : ''}`}>
      <span className="settings-desc">{label}</span>
      <button className={`toggle-switch ${on ? 'on' : ''}`} onClick={onClick} disabled={off} aria-pressed={on}
        aria-label={`Переключить ${name}`}>
        <span className="toggle-thumb" />
      </button>
    </div>
  );
}

export default function SettingsTab({ settings, onLogout, profile, onNeedProfile }) {
  const {
    theme, fontSize, contrast, custom, toggleTheme, toggleContrast, toggleCustom, setCustomColor, applyPreset, setFontSize,
  } = settings;
  // При включении своей темы цвета сразу раскрываются — иначе не видно, что делать дальше.
  const [editing, setEditing] = useState(false);
  // Своя тема плохо читается — раздел темы пишется чёрным или белым, чтобы её было чем исправить.
  const unreadable = custom.enabled && custom.colors && readabilityIssues(custom.colors).length > 0;

  // После регистрации данных ещё нет — запрашиваем их один раз при открытии настроек.
  useEffect(() => {
    if (!profile && onNeedProfile) onNeedProfile();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="tab-view">
      <h2 className="view-title">Настройки</h2>

      <ProfileCard profile={profile} />

      <div className={`settings-section ${unreadable ? 'theme-safe' : ''}`}>
        <div className="settings-label">Тема оформления</div>
        <Toggle label="Ночной режим" name="ночной режим" on={theme === 'dark'} off={custom.enabled} spaced={false}
          onClick={toggleTheme} />
        <Toggle label="Контрастный режим" name="контрастный режим" on={contrast} off={custom.enabled}
          onClick={toggleContrast} />
        <Toggle label="Своя тема" name="свою тему" on={custom.enabled}
          onClick={() => { if (!custom.enabled) setEditing(true); toggleCustom(); }} />
        {custom.enabled && (
          <>
            <button type="button" className="secondary-btn theme-edit-btn" aria-expanded={editing}
              onClick={() => setEditing(!editing)}>
              <Icon name="palette" /><span>{editing ? 'Свернуть цвета' : 'Настроить цвета'}</span>
            </button>
            {editing && custom.colors && (
              <ThemeEditor colors={custom.colors} onChange={setCustomColor} onPreset={applyPreset} />
            )}
          </>
        )}
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
