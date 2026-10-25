import { useEffect, useState } from 'react';
import { getLaunchScreen } from '../telegram/webApp';
import { useBackButton, useSettingsButton } from '../telegram/useTelegramButtons';

// Главный экран роли. Остальные экраны открываются поверх него и закрываются кнопкой «Назад».
const ROOTS = { student: 'works', teacher: 'queue', admin: 'admin' };

// Экраны, которые можно открыть ссылкой из бота (?open=…): у каждой роли свои.
const LAUNCH = {
  student: { upload: 'upload', works: 'works' },
  teacher: { queue: 'queue' },
  admin: { admin: 'admin' },
};

// Ссылка из бота срабатывает один раз — при входе, а не после каждой смены роли.
let launchUsed = false;

const initialStack = (role) => {
  const root = { screen: ROOTS[role], params: {} };
  if (launchUsed) return [root];
  launchUsed = true;
  const launch = getLaunchScreen();
  const work = launch.match(/^work-(\d+)$/);
  if (work && role !== 'admin') return [root, { screen: 'work', params: { id: Number(work[1]) } }];
  const screen = LAUNCH[role]?.[launch];
  return screen && screen !== root.screen ? [root, { screen, params: {} }] : [root];
};

// Только для тестов: ссылка из бота снова действует.
export const resetLaunchScreen = () => { launchUsed = false; };

/**
 * Стек экранов вместо вкладок: главный экран роли, поверх — работа, загрузка, настройки, заметки.
 * «Назад» — родная кнопка Telegram, а вне Telegram — стрелка в заголовке (nativeBack = false).
 */
export function useNavigator({ role, active }) {
  const [stack, setStack] = useState([]);
  const enabled = active && Boolean(ROOTS[role]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- стек строится заново при входе под ролью
    setStack(enabled ? initialStack(role) : []);
  }, [enabled, role]);

  const current = stack[stack.length - 1] || null;
  const canGoBack = stack.length > 1;

  const push = (screen, params = {}) => setStack((s) => [...s, { screen, params }]);
  const back = () => setStack((s) => (s.length > 1 ? s.slice(0, -1) : s));
  const replace = (screen, params = {}) => setStack((s) => [...s.slice(0, -1), { screen, params }]);
  const home = (params = {}) => setStack([{ screen: ROOTS[role], params }]);

  const nativeBack = useBackButton(canGoBack ? back : null);
  useSettingsButton(enabled && current?.screen !== 'settings' ? () => push('settings') : null);

  return { current, depth: stack.length, canGoBack, nativeBack, push, back, replace, home };
}
