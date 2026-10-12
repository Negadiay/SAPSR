import { apiFetch, postJson } from './client';

export const fetchMe = () => apiFetch('/me');

export const registerStudent = (fullName) => postJson('/register', { role: 'STUDENT', full_name: fullName });

export const registerTeacher = (email, code) => postJson('/register', { role: 'TEACHER', full_name: '', email, code });

export const sendTeacherCode = (email) => postJson('/register/send-code', { email });
