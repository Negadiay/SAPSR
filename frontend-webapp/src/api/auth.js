import { apiFetch, postJson } from './client';

export const fetchMe = () => apiFetch('/me');

// Студент — по полям (api-v1.md, раздел 4): { last_name, first_name, middle_name?, group }.
export const registerStudent = (fields) => postJson('/register', { role: 'STUDENT', ...fields });

// Исправление своего ФИО студентом (раздел 5); группу меняет администратор.
export const updateMyName = (fields) => apiFetch('/me', {
  method: 'PATCH',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(fields),
});

export const registerTeacher = (email, code) => postJson('/register', { role: 'TEACHER', full_name: '', email, code });

export const sendTeacherCode = (email) => postJson('/register/send-code', { email });
