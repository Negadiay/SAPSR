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

// Родная кнопка Telegram: запоминает параметры и обработчик, click() — как нажатие пользователя.
const nativeButton = () => {
  const button = { visible: false, params: {}, handlers: new Set() };
  Object.assign(button, {
    show: vi.fn(() => { button.visible = true; }),
    hide: vi.fn(() => { button.visible = false; }),
    onClick: vi.fn((h) => button.handlers.add(h)),
    offClick: vi.fn((h) => button.handlers.delete(h)),
    setParams: vi.fn((p) => { Object.assign(button.params, p); button.visible = p.is_visible ?? button.visible; }),
    showProgress: vi.fn(),
    hideProgress: vi.fn(),
    click: () => button.handlers.forEach((h) => h()),
  });
  return button;
};

// Мини-приложение, открытое пользователем Telegram с указанным id.
// native — клиент Telegram с родными кнопками «Назад», главной и «Настройки» (Bot API 7+).
export function stubTelegram({ userId = 42, platform = 'unknown', native = false, colorScheme } = {}) {
  const webApp = {
    initData: 'user=' + encodeURIComponent(JSON.stringify({ id: userId })),
    platform,
    colorScheme,
    ready: vi.fn(),
    expand: vi.fn(),
    HapticFeedback: { notificationOccurred: vi.fn() },
  };
  if (native) {
    Object.assign(webApp, {
      isVersionAtLeast: () => true,
      BackButton: nativeButton(),
      MainButton: nativeButton(),
      SettingsButton: nativeButton(),
      disableVerticalSwipes: vi.fn(),
      setHeaderColor: vi.fn(),
      setBackgroundColor: vi.fn(),
      setBottomBarColor: vi.fn(),
      enableClosingConfirmation: vi.fn(),
      disableClosingConfirmation: vi.fn(),
      onEvent: vi.fn(),
      offEvent: vi.fn(),
    });
  }
  window.Telegram = { WebApp: webApp };
  return webApp;
}
