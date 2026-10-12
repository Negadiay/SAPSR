import { vi } from 'vitest';

export const jsonResponse = (data, status = 200) => new Response(JSON.stringify(data), { status });

// Подменяет fetch. routes: { 'GET /me': данные | Response | ({ body }) => данные или Response }.
// Возвращает журнал запросов; путь записывается без префикса /api/v1.
export function mockApi(routes) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET';
    const path = String(url).replace(/^\/api\/v1/, '');
    const body = typeof options.body === 'string' ? JSON.parse(options.body) : options.body;
    calls.push({ method, path, body, headers: options.headers });
    const route = routes[`${method} ${path}`];
    if (route === undefined) return jsonResponse({ error: `Нет заглушки для ${method} ${path}` }, 404);
    const result = typeof route === 'function' ? route({ body }) : route;
    return result instanceof Response ? result : jsonResponse(result);
  }));
  return {
    calls,
    find: (method, path) => calls.filter(c => c.method === method && c.path === path),
  };
}

// Мини-приложение, открытое пользователем Telegram с указанным id.
export function stubTelegram({ userId = 42, platform = 'unknown' } = {}) {
  const webApp = {
    initData: 'user=' + encodeURIComponent(JSON.stringify({ id: userId })),
    platform,
    ready: vi.fn(),
    expand: vi.fn(),
    showPopup: vi.fn(),
    HapticFeedback: { notificationOccurred: vi.fn() },
  };
  window.Telegram = { WebApp: webApp };
  return webApp;
}
