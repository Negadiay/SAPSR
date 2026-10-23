import { apiFetch, putJson } from './client';

// Экран администратора (api-v1.md, раздел 14): каждый метод возвращает экран целиком.
export const fetchCalendar = () => apiFetch('/admin/calendar');

// half: 'AUTUMN' | 'SPRING' | 'AUTO' — AUTO: снова по дате.
export const saveHalf = (half) => putJson('/admin/calendar/half', { half });

// dates: { 1: 'ГГГГ-ММ-ДД' | null, 2: …, 3: … }
export const saveProcentovki = (dates) => putJson('/admin/calendar/procentovki', dates);

export const saveGroupCourse = (group, course) => putJson(`/admin/groups/${group}/course`, { course });

export const resetGroupCourse = (group) => apiFetch(`/admin/groups/${group}/course`, { method: 'DELETE' });
