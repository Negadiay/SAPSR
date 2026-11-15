import { useState } from 'react';
import NameFields from '../../components/NameFields';
import StatusLine from '../../components/StatusLine';
import { updateMyName } from '../../api/auth';
import { readError } from '../../api/client';
import { nameFormBody, nameFormOf, nameFormProblem } from '../../lib/names';

/**
 * «Мои данные» студента (решение 4): ФИО можно исправить самому, группу меняет администратор (решение 15).
 * Аккаунту старой формы (известны только инициалы) предлагается дополнить ФИО.
 */
export default function MyData({ profile, onSaved }) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState(() => nameFormOf(profile));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const problem = nameFormProblem(form);

  const save = async (e) => {
    e.preventDefault();
    if (problem || saving) return;
    setSaving(true);
    setError('');
    try {
      const res = await updateMyName(nameFormBody(form));
      if (res.ok) {
        onSaved?.(await res.json().catch(() => null));
        setEditing(false);
      } else {
        const err = await readError(res);
        setError(err.message || err.error || 'Не удалось сохранить.');
      }
    } catch {
      setError('Нет связи с сервером. Попробуйте позже.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="card settings-card">
      <p className="settings-label">Мои данные</p>
      {profile.namesake_number != null && (
        <p className="hint">Среди студентов группы с таким же ФИО вы — №{profile.namesake_number}.</p>
      )}
      {profile.profile_incomplete && !editing && (
        <p className="hint">Укажите имя и отчество полностью — так преподавателю проще вас найти.</p>
      )}
      {editing ? (
        <form className="form" onSubmit={save}>
          <NameFields value={form} onChange={setForm} idPrefix="me" />
          {error && <StatusLine tone="error">{error}</StatusLine>}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={Boolean(problem) || saving}>
              {saving ? 'Сохранение…' : 'Сохранить'}
            </button>
            <button type="button" className="btn btn-secondary" disabled={saving}
              onClick={() => { setEditing(false); setError(''); setForm(nameFormOf(profile)); }}>
              Отмена
            </button>
          </div>
        </form>
      ) : (
        <button type="button" className="btn btn-secondary btn-block" onClick={() => setEditing(true)}>
          {profile.profile_incomplete ? 'Дополнить ФИО' : 'Исправить ФИО'}
        </button>
      )}
    </section>
  );
}