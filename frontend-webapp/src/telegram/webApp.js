// Обёртка над window.Telegram.WebApp: вне Telegram вызовы ничего не делают.
const webApp = () => window.Telegram?.WebApp;

export const getInitData = () => webApp()?.initData || '';

export const getTelegramUserId = () => {
  try {
    const user = new URLSearchParams(getInitData()).get('user');
    return user ? String(JSON.parse(user).id || '') : '';
  } catch {
    return '';
  }
};

export const getPlatform = () => webApp()?.platform || '';

export const isTelegramClient = () => {
  const platform = getPlatform();
  return Boolean(platform) && platform !== 'unknown';
};

export const initWebApp = () => {
  webApp()?.ready();
  webApp()?.expand();
};

export const notifyHaptic = (type) => webApp()?.HapticFeedback?.notificationOccurred(type);

export const showPopup = (params) => webApp()?.showPopup(params);
