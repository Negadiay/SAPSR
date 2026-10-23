import { useEffect, useState } from 'react';
import { fetchCalendar, resetGroupCourse, saveGroupCourse, saveHalf, saveProcentovki } from '../../api/admin';
import { readError } from '../../api/client';

// Состояние экрана администратора: учебный календарь с сервера и сохранение исправлений.
export function useAdminCalendar({ active }) {
  const [calendar, setCalendar] = useState(null);
  const [error, setError]       = useState('');
  const [saving, setSaving]     = useState(false);

  // Сервер в ответ на любое изменение присылает экран целиком — показываем его.
  const apply = async (request) => {
    setSaving(true);
    setError('');
    try {
      const res = await request();
      if (res.ok) setCalendar(await res.json());
      else setError((await readError(res)).error || `Ошибка ${res.status}`);
    } catch { setError('Нет соединения с сервером.'); }
    finally { setSaving(false); }
  };

  useEffect(() => {
    if (active) apply(fetchCalendar);
  }, [active]);

  return {
    calendar, error, saving,
    changeHalf: (half) => apply(() => saveHalf(half)),
    changeProcentovki: (dates) => apply(() => saveProcentovki(dates)),
    // course: 1–4 или null — снова по номеру группы.
    changeCourse: (group, course) => apply(() => (course ? saveGroupCourse(group, course) : resetGroupCourse(group))),
  };
}
