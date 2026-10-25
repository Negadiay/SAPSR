import { useEffect, useState } from 'react';
import { shortName } from '../../lib/names';
import AppHeader from '../../components/AppHeader';
import ConfirmDialog from '../../components/ConfirmDialog';
import Icon from '../../components/Icon';
import { isTelegramClient } from '../../telegram/webApp';
import ThemeEditor from './ThemeEditor';
import { readabilityIssues } from './customTheme';
import { FONT_SIZES } from './useSettings';
import './settings.css';

const FONT_SIZE_LABELS = { small: 'Мелкий', normal: 'Обычный', large: 'Крупный', xlarge: 'Очень крупный' };
const ROLE_LABELS = { STUDENT: 'Студент', TEACHER: 'Преподаватель', ADMIN: 'Администратор' };

// Кто вошёл: «Фамилия И. О.», у студента — ещё группа и курс. У администратора имени может не быть.
function ProfileCard({ profile }) {
  if (!profile) return null;
  const name = shortName(profile);
  return (
    <section className="card settings-card">
      <p className="settings-label">{ROLE_LABELS[profile.role] || 'Профиль'}</p>
      {name && <p className="settings-name">{name}</p>}
      {profile.role === 'STUDENT' && profile.group && (
        <p className="hint">Группа {profile.group}{profile.course ? ` · ${profile.course} курс` : ''}</p>
      )}
    </section>
  );
}

function Toggle({ label, on, off = false, onClick }) {
  return (
    <div className={`settings-row ${off ? 'settings-row-off' : ''}`}>
      <span>{label}</span>
      <button type="button" className={`toggle ${on ? 'toggle-on' : ''}`} onClick={onClick} disabled={off}
        role="switch" aria-checked={on} aria-label={label}>
        <span className="toggle-thumb" />
      </button>
    </div>
  );
}

export default function SettingsScreen({ settings, profile, header, onNeedProfile, onChangeRole }) {
  const {
    mode, setMode, contrast, toggleContrast, custom, toggleCustom, setCustomColor, applyPreset, fontSize, setFontSize,
  } = settings;
  const [editing, setEditing] = useState(false);
  const [changingRole, setChangingRole] = useState(false);
  // Своя тема плохо читается — раздел темы пишется чёрным или белым, чтобы её было чем исправить.
  const unreadable = custom.enabled && custom.colors && readabilityIssues(custom.colors).length > 0;
  const modes = [['auto', isTelegramClient() ? 'Как в Telegram' : 'Как в системе'], ['light', 'Светлая'], ['dark', 'Тёмная']];

  // После регистрации данных ещё нет — запрашиваем их один раз при открытии настроек.
  useEffect(() => {
    if (!profile && onNeedProfile) onNeedProfile();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="screen-body">
      <AppHeader title="Настройки" {...header} />
      <ProfileCard profile={profile} />

      <section className={`card settings-card ${unreadable ? 'theme-safe' : ''}`}>
        <p className="settings-label">Тема</p>
        <div className={`radio-list ${custom.enabled ? 'settings-row-off' : ''}`} role="radiogroup" aria-label="Тема">
          {modes.map(([id, label]) => (
            <label key={id} className="radio-row">
              <input type="radio" name="theme-mode" value={id} checked={mode === id} disabled={custom.enabled}
                onChange={() => setMode(id)} />
              <span>{label}</span>
            </label>
          ))}
        </div>
        <Toggle label="Высокий контраст" on={contrast} off={custom.enabled} onClick={toggleContrast} />
        <Toggle label="Своя тема" on={custom.enabled}
          onClick={() => { if (!custom.enabled) setEditing(true); toggleCustom(); }} />
        {custom.enabled && (
          <>
            <button type="button" className="btn btn-secondary btn-block" aria-expanded={editing}
              onClick={() => setEditing(!editing)}>
              <Icon name="palette" /><span>{editing ? 'Свернуть цвета' : 'Настроить цвета'}</span>
            </button>
            {editing && custom.colors && (
              <ThemeEditor colors={custom.colors} onChange={setCustomColor} onPreset={applyPreset} />
            )}
          </>
        )}
      </section>

      <section className="card settings-card">
        <p className="settings-label">Размер текста</p>
        <div className="fontsize-row">
          <span className="fontsize-small" aria-hidden="true">A</span>
          <input className="fontsize-slider" type="range" min="0" max={FONT_SIZES.length - 1} step="1"
            value={Math.max(0, FONT_SIZES.indexOf(fontSize))} aria-label="Размер текста"
            aria-valuetext={FONT_SIZE_LABELS[fontSize]}
            onChange={(e) => setFontSize(FONT_SIZES[Number(e.target.value)] || 'normal')} />
          <span className="fontsize-large" aria-hidden="true">A</span>
        </div>
        <p className="hint">{FONT_SIZE_LABELS[fontSize]}</p>
      </section>

      {onChangeRole && (
        <button type="button" className="btn btn-ghost-danger btn-block" onClick={() => setChangingRole(true)}>
          Сменить роль
        </button>
      )}

      {changingRole && (
        <ConfirmDialog title="Сменить роль?" danger confirmLabel="Сменить"
          body="Понадобится зарегистрироваться заново. Прежняя роль сохранится, пока вы не зарегистрируетесь в новой."
          onCancel={() => setChangingRole(false)} onConfirm={() => { setChangingRole(false); onChangeRole(); }} />
      )}
    </div>
  );
}
