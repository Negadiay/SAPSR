import { useEffect, useState } from 'react';
import {
  deleteGroup, fetchCalendar, fetchPeople, makeAdmin, resetGroupCourse, saveGroupCourse, saveHalf, saveSchedule,
  saveUser,
} from '../../api/admin';
import { readError } from '../../api/client';

// Состояние экрана администратора: календарь с сервера, люди выбранной группы, сохранение исправлений.
export function useAdminCalendar({ active }) {
  const [calendar, setCalendar] = useState(null);
  const [people, setPeople]     = useState(null);
  const [error, setError]       = useState('');
  const [saving, setSaving]     = useState(false);

  // Сервер в ответ на изменение присылает экран целиком, а после правки человека — и людей его группы.
  const apply = async (request, { onPeople } = {}) => {
    setSaving(true);
    setError('');
    try {
      const res = await request();
      if (!res.ok) {
        setError((await readError(res)).error || `Ошибка ${res.status}`);
        return false;
      }
      const body = await res.json();
      if (onPeople) setPeople(body);
      else {
        setCalendar(body);
        if (body.people) setPeople(body.people);
      }
      return true;
    } catch {
      setError('Нет соединения с сервером.');
      return false;
    } finally { setSaving(false); }
  };

  useEffect(() => {
    if (active) apply(fetchCalendar);
  }, [active]);

  const openGroup = (group) => {
    if (!group) { setPeople(null); return; }
    setPeople({ group, students: null, teachers: null });
    apply(() => fetchPeople(group), { onPeople: true });
  };

  return {
    calendar, people, error, saving, openGroup,
    changeHalf: (half) => apply(() => saveHalf(half)),
    changeSchedule: (courses, dates) => apply(() => saveSchedule(courses, dates)),
    // course: 1–4 или null — снова по номеру группы.
    changeCourse: (group, course) => apply(() => (course ? saveGroupCourse(group, course) : resetGroupCourse(group))),
    removeGroup: (group) => apply(() => deleteGroup(group)),
    changeUser: (id, data) => apply(() => saveUser(id, data)),
    // После назначения человек уходит из группы — обновляем и её список.
    appointAdmin: async (id) => {
      const ok = await apply(() => makeAdmin(id));
      if (ok && people?.group) await apply(() => fetchPeople(people.group), { onPeople: true });
      return ok;
    },
  };
}
