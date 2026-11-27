import { apiFetch, putJson, postJson } from './client';

// Экран администратора (api-v1.md, раздел 14): изменения возвращают экран целиком.
export const fetchCalendar = () => apiFetch('/admin/calendar');

// half: 'AUTUMN' | 'SPRING' | 'AUTO' — AUTO: снова по дате.
export const saveHalf = (half) => putJson('/admin/calendar/half', { half });

// courses: [1..4]; dates: { 1, 2, 3, deadline } — 'ГГГГ-ММ-ДД' или null.
export const saveSchedule = (courses, dates) => putJson('/admin/calendar/schedule', { courses, dates });

export const saveGroupCourse = (group, course) => putJson(`/admin/groups/${group}/course`, { course });

export const resetGroupCourse = (group) => apiFetch(`/admin/groups/${group}/course`, { method: 'DELETE' });

// Только пустую группу, добавленную вручную (manual в экране).
export const deleteGroup = (group) => apiFetch(`/admin/groups/${group}`, { method: 'DELETE' });

// Студенты и преподаватели группы; id — ключ для изменений, на экран не выводится.
export const fetchPeople = (group) => apiFetch(`/admin/groups/${group}/people`);

// { name, group } — только студента: преподавателей администратор лишь просматривает.
export const saveUser = (id, data) => putJson(`/admin/users/${id}`, data);

export const makeAdmin = (id) => postJson(`/admin/users/${id}/admin`, {});

// «Красная зона» (api-v1.md, раздел 16): состояние обработчика и модель проверки.
export const fetchSystem = () => apiFetch('/admin/system');

// model: id из списка обработчика или null — снова по умолчанию; облачную — только с confirmCloud.
export const saveModel = (model, confirmCloud = false) =>
  putJson('/admin/system/model', { model, confirm_cloud: confirmCloud });
