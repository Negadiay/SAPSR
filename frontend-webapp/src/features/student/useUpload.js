import { useState, useEffect } from 'react';
import { fetchTeachers, uploadSubmission } from '../../api/submissions';
import { readError } from '../../api/client';
import { notifyHaptic, setClosingConfirmation } from '../../telegram/webApp';

const LAST_TEACHER_KEY = 'sapsr_last_teacher_id';
const MAX_FILE_BYTES = 50 * 1024 * 1024;

const readStorage = (key) => {
  try { return localStorage.getItem(key); } catch { return null; }
};

const writeStorage = (key, value) => {
  try { localStorage.setItem(key, value); } catch { /* без памяти — не страшно */ }
};

// Проверка до отправки: сервер примет только PDF до 50 МБ.
const fileProblem = (file) => {
  if (!file) return '';
  if (!/\.pdf$/i.test(file.name) && file.type !== 'application/pdf') return 'Нужен файл PDF.';
  if (file.size > MAX_FILE_BYTES) return 'Файл больше 50 МБ.';
  if (file.size === 0) return 'Файл пустой.';
  return '';
};

const uploadError = async (res) => {
  if (res.status === 413) return 'Файл больше 50 МБ.';
  if (res.status === 401 || res.status === 403) return 'Сессия устарела. Закройте и откройте приложение.';
  if (res.status >= 500) return 'Сервер не ответил. Попробуйте позже.';
  return (await readError(res)).error || 'Не удалось отправить работу.';
};

/**
 * Форма загрузки работы. preset — преподаватель прошлой версии («Загрузить новую версию»).
 * Преподаватель выбирается сам, только если он один или его выбирали раньше: отправить не тому — хуже, чем выбрать.
 * Вид работы не спрашиваем: сервер определяет его по семестру и титульному листу (решение 11).
 */
export function useUpload({ active, preset, onUploaded }) {
  const [teachers, setTeachers]     = useState([]);
  const [teachersLoaded, setLoaded] = useState(false);
  const [teacherId, setTeacherId]   = useState(preset?.teacherId ? String(preset.teacherId) : '');
  const [file, setFile]             = useState(null);
  const [uploading, setUploading]   = useState(false);
  const [error, setError]           = useState('');

  useEffect(() => {
    if (!active) return undefined;
    let cancelled = false;
    (async () => {
      try {
        const res = await fetchTeachers();
        const data = res.ok ? await res.json() : [];
        if (cancelled) return;
        const list = Array.isArray(data) ? data : [];
        setTeachers(list);
        setTeacherId((current) => {
          if (current && list.some((t) => String(t.telegram_id) === current)) return current;
          const saved = readStorage(LAST_TEACHER_KEY);
          if (saved && list.some((t) => String(t.telegram_id) === saved)) return saved;
          return list.length === 1 ? String(list[0].telegram_id) : '';
        });
      } catch { /* список пуст — форма подскажет */ }
      if (!cancelled) setLoaded(true);
    })();
    return () => { cancelled = true; };
  }, [active]);

  const selectTeacher = (id) => {
    setTeacherId(id);
    if (id) writeStorage(LAST_TEACHER_KEY, id);
  };

  const selectFile = (next) => { setFile(next || null); setError(''); };

  const problem = fileProblem(file);
  const ready = Boolean(file && !problem && teacherId);

  const submit = async (e) => {
    e?.preventDefault();
    if (!ready || uploading) return;
    setUploading(true);
    setError('');
    setClosingConfirmation(true);
    try {
      const res = await uploadSubmission(file, teacherId);
      if (res.ok) {
        const body = await res.json().catch(() => ({}));
        notifyHaptic('success');
        setFile(null);
        await onUploaded?.(body.db_id);
      } else {
        notifyHaptic('error');
        setError(await uploadError(res));
      }
    } catch {
      setError('Нет связи. Проверьте интернет.');
    } finally {
      setUploading(false);
      setClosingConfirmation(false);
    }
  };

  return {
    teachers, teachersLoaded, teacherId, file, problem, ready, uploading, error,
    selectTeacher, selectFile, submit,
  };
}
