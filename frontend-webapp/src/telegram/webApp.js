// Обёртка над window.Telegram.WebApp: вне Telegram и в старых клиентах вызовы ничего не делают,
// а интерфейс показывает свои кнопки вместо родных.
const webApp = () => window.Telegram?.WebApp;

const supports = (version) => {
  try {
    return Boolean(webApp()?.isVersionAtLeast?.(version));
  } catch {
    return false;
  }
};

export const getInitData = () => webApp()?.initData || '';

export const getTelegramUserId = () => {
  try {
    const user = new URLSearchParams(getInitData()).get('user');
    return user ? String(JSON.parse(user).id || '') : '';
  } catch {
    return '';
  }
};

const getPlatform = () => webApp()?.platform || '';

export const isTelegramClient = () => {
  const platform = getPlatform();
  return Boolean(platform) && platform !== 'unknown';
};

export const initWebApp = () => {
  webApp()?.ready();
  webApp()?.expand();
  // Свайп вниз по списку не должен закрывать приложение (Bot API 7.7).
  if (supports('7.7')) webApp().disableVerticalSwipes?.();
};

export const notifyHaptic = (type) => webApp()?.HapticFeedback?.notificationOccurred(type);

// Экран, на котором открыть приложение: ?open=… в адресе кнопки бота или параметр ссылки t.me/…?startapp=…
export const getLaunchScreen = () => {
  try {
    const fromUrl = new URLSearchParams(window.location.search).get('open');
    return fromUrl || webApp()?.initDataUnsafe?.start_param || '';
  } catch {
    return '';
  }
};

// Светлая или тёмная тема Telegram; вне Telegram — тема системы.
export const getColorScheme = () => webApp()?.colorScheme
  || (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');

export const onThemeChanged = (handler) => {
  const app = webApp();
  if (app?.onEvent) {
    app.onEvent('themeChanged', handler);
    return () => app.offEvent?.('themeChanged', handler);
  }
  const media = window.matchMedia?.('(prefers-color-scheme: dark)');
  media?.addEventListener?.('change', handler);
  return () => media?.removeEventListener?.('change', handler);
};

// Шапка и фон Telegram — в цвет приложения, чтобы не было полосы другого цвета.
export const paintChrome = (background) => {
  const app = webApp();
  if (!app || !background) return;
  if (supports('6.1')) {
    app.setHeaderColor?.(background);
    app.setBackgroundColor?.(background);
  }
  if (supports('7.10')) app.setBottomBarColor?.(background);
};

// Родные кнопки Telegram: «Назад» в шапке (6.1), главная кнопка внизу экрана, «Настройки» в меню (7.0).
export const hasBackButton = () => Boolean(webApp()?.BackButton) && supports('6.1');
export const hasMainButton = () => Boolean(webApp()?.MainButton) && supports('6.1');
export const hasSettingsButton = () => Boolean(webApp()?.SettingsButton) && supports('7.0');

export const backButton = () => webApp().BackButton;
export const mainButton = () => webApp().MainButton;
export const settingsButton = () => webApp().SettingsButton;

// Пока идёт загрузка файла, закрыть приложение можно только после подтверждения (6.2).
export const setClosingConfirmation = (on) => {
  if (!supports('6.2')) return;
  if (on) webApp().enableClosingConfirmation?.();
  else webApp().disableClosingConfirmation?.();
};
