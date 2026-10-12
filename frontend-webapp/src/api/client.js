import { getInitData } from '../telegram/webApp';

export const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1';

export const apiFetch = (path, { headers, ...options } = {}) => fetch(`${API_BASE}${path}`, {
  ...options,
  headers: { 'Authorization': getInitData(), ...headers },
});

export const postJson = (path, body) => apiFetch(path, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

export const readError = (res) => res.json().catch(() => ({}));

// Список с сервера; при ошибке пишет её в консоль и возвращает null.
export const fetchList = async (path) => {
  try {
    const res = await apiFetch(path);
    if (res.ok) {
      const data = await res.json();
      return Array.isArray(data) ? data : [];
    }
    const body = await res.text().catch(() => '');
    console.error(`[SAPSR] GET ${path} →`, res.status, body);
  } catch (err) { console.error(`[SAPSR] GET ${path} network error:`, err); }
  return null;
};
