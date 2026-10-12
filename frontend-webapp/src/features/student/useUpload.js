import { useState, useEffect } from 'react';
import { fetchTeachers, uploadSubmission } from '../../api/submissions';
import { readError } from '../../api/client';
import { notifyHaptic, showPopup } from '../../telegram/webApp';

const LAST_TEACHER_KEY = 'sapsr_last_teacher_id';

const teacherIdOf = (teacher) => String(teacher.telegram_id || teacher.id || 0);

// Форма загрузки работы: список преподавателей, выбранный файл, отправка.
export function useUpload({ active, onUploaded }) {
  const [file, setFile]                           = useState(null);
  const [status, setStatus]                       = useState('');
  const [teachers, setTeachers]                   = useState([]);
  const [selectedTeacherId, setSelectedTeacherId] = useState('');
  const [uploading, setUploading]                 = useState(false);

  const loadTeachers = async () => {
    try {
      const res = await fetchTeachers();
      if (res.ok) {
        const data = await res.json();
        setTeachers(data);
        if (data.length > 0) {
          const savedTeacherId = localStorage.getItem(LAST_TEACHER_KEY);
          const savedExists = data.some(t => teacherIdOf(t) === savedTeacherId);
          setSelectedTeacherId(savedExists ? savedTeacherId : teacherIdOf(data[0]));
        }
      }
    } catch (err) { console.warn('Teachers error:', err); }
  };

  useEffect(() => {
    if (active) loadTeachers();
  }, [active]);

  const selectTeacher = (teacherId) => {
    setSelectedTeacherId(teacherId);
    localStorage.setItem(LAST_TEACHER_KEY, teacherId);
  };

  const selectFile = (nextFile) => { setFile(nextFile); setStatus(''); };

  const clearStatus = () => setStatus('');

  const submit = async (e) => {
    e.preventDefault();
    if (!file || uploading) return;
    setUploading(true); setStatus('⏳ Загрузка...');
    try {
      if (selectedTeacherId) localStorage.setItem(LAST_TEACHER_KEY, selectedTeacherId);
      const res = await uploadSubmission(file, selectedTeacherId);
      if (res.ok) {
        setStatus('✅ Работа успешно отправлена!'); setFile(null);
        notifyHaptic('success');
        onUploaded();
        showPopup({ title: 'Готово!', message: 'Файл отправлен на проверку.', buttons: [{ type: 'ok' }] });
      } else {
        notifyHaptic('error');
        if (res.status === 413) {
          setStatus('❌ Файл слишком большой. Максимальный размер — 50 МБ.');
        } else if (res.status === 415 || res.status === 400) {
          const err = await readError(res);
          setStatus(`❌ ${err.error || 'Недопустимый формат файла. Загрузите PDF.'}`);
        } else if (res.status === 401 || res.status === 403) {
          setStatus('❌ Ошибка авторизации. Перезапустите приложение.');
        } else if (res.status >= 500) {
          setStatus('❌ Ошибка сервера. Попробуйте чуть позже.');
        } else {
          const err = await readError(res);
          setStatus(`❌ ${err.error || `Ошибка ${res.status}`}`);
        }
      }
    } catch { setStatus('❌ Нет соединения с сервером. Проверьте интернет.'); }
    finally { setUploading(false); }
  };

  return {
    file, status, teachers, selectedTeacherId, uploading,
    selectTeacher, selectFile, clearStatus, submit,
  };
}
