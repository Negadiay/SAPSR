import { useState, useEffect } from 'react';
import { fetchTeachers, uploadSubmission } from '../../api/submissions';
import { readError } from '../../api/client';
import { notifyHaptic, setClosingConfirmation } from '../../telegram/webApp';

const LAST_TEACHER_KEY = 'sapsr_last_teacher_id';

// Ключ преподавателя из GET /teachers: поле id (Telegram ID студенту не отдаётся); telegram_id — прежние ответы.
export const teacherKey = (t) => String(t.id ?? t.telegram_id ?? '');
// Запасной предел, если сервер его не сообщил; настоящий — max_upload_mb из GET /me (решение 15).
export const DEFAULT_MAX_UPLOAD_MB = 50;

// 50 МБ — предел Telegram на отправку файла ботом: работа уходит преподавателю документом в чат.
export const tooLargeText = (maxMb) =>
  `Файл больше ${maxMb} МБ — Telegram не даст переслать его преподавателю. Сожмите PDF (картинки) и загрузите снова.`;

const readStorage = (key) => {
  try { return localStorage.getItem(key); } catch { return null; }
};

const writeStorage = (key, value) => {
  try { localStorage.setItem(key, value); } catch { /* без памяти — не страшно */ }
};

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

// Проверка до отправки: сервер примет PDF или DOCX не больше предела (вид файла он проверит по содержимому).
const fileProblem = (file, maxMb) => {
  if (!file) return '';
  if (!/\.(pdf|docx)$/i.test(file.name) && file.type !== 'application/pdf' && file.type !== DOCX_TYPE) {
    return 'Нужен файл PDF или DOCX.';
  }
  if (file.size > maxMb * 1024 * 1024) return tooLargeText(maxMb);
  if (file.size === 0) return 'Файл пустой.';
  return '';
};

const uploadError = async (res, maxMb) => {
  if (res.status === 413) return (await readError(res)).message || tooLargeText(maxMb);
  if (res.status === 401 || res.status === 403) return 'Сессия устарела. Закройте и откройте приложение.';
  if (res.status >= 500) return 'Сервер не ответил. Попробуйте позже.';
  const err = await readError(res);
  return err.message || err.error || 'Не удалось отправить работу.';
};

/**
 * Форма загрузки работы. preset — преподаватель прошлой версии («Загрузить новую версию»).
 * Преподаватель выбирается сам, только если он один или его выбирали раньше: отправить не тому — хуже, чем выбрать.
 * Вид работы не спрашиваем: сервер определяет его по семестру и титульному листу (решение 11).
 */
export function useUpload({ active, preset, onUploaded, maxUploadMb }) {
  const maxMb = Number(maxUploadMb) > 0 ? Number(maxUploadMb) : DEFAULT_MAX_UPLOAD_MB;
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
          if (current && list.some((t) => teacherKey(t) === current)) return current;
          const saved = readStorage(LAST_TEACHER_KEY);
          if (saved && list.some((t) => teacherKey(t) === saved)) return saved;
          return list.length === 1 ? teacherKey(list[0]) : '';
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

  const problem = fileProblem(file, maxMb);
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
        setError(await uploadError(res, maxMb));
      }
    } catch {
      setError('Нет связи. Проверьте интернет.');
    } finally {
      setUploading(false);
      setClosingConfirmation(false);
    }
  };

  return {
    teachers, teachersLoaded, teacherId, file, problem, ready, uploading, error, maxMb,
    selectTeacher, selectFile, submit,
  };
}
