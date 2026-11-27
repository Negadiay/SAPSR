import { useEffect, useRef } from 'react';
import {
  backButton, hasBackButton, hasMainButton, hasSettingsButton, mainButton, settingsButton,
} from './webApp';

// Цвета главной кнопки — как у кнопок приложения; ставит каркас при смене темы.
let mainColors = null;
export const setMainButtonColors = (colors) => { mainColors = colors; };

// Обработчик держим в ref: у Telegram подписка по ссылке на функцию, а функции компонентов новые на каждом рендере.
function useStableHandler(handler) {
  const ref = useRef(handler);
  useEffect(() => { ref.current = handler; });
  return ref;
}

/**
 * Кнопка «Назад» в шапке Telegram. onBack — null, когда возвращаться некуда.
 * Возвращает true, если кнопка родная, — тогда своя стрелка в заголовке не нужна.
 */
export function useBackButton(onBack) {
  const native = hasBackButton();
  const handler = useStableHandler(onBack);
  const visible = Boolean(onBack);

  useEffect(() => {
    if (!native) return undefined;
    const click = () => handler.current?.();
    backButton().onClick(click);
    return () => backButton().offClick(click);
  }, [native, handler]);

  useEffect(() => {
    if (!native) return;
    if (visible) backButton().show();
    else backButton().hide();
  }, [native, visible]);

  return native;
}

/**
 * Главная кнопка внизу экрана Telegram — для основного действия экрана. Возвращает true, если кнопка родная;
 * иначе экран рисует свою кнопку.
 */
export function useMainButton({ text, onClick, disabled = false, progress = false, visible = true }) {
  const native = hasMainButton();
  const handler = useStableHandler(onClick);

  useEffect(() => {
    if (!native) return undefined;
    const click = () => handler.current?.();
    mainButton().onClick(click);
    return () => {
      mainButton().offClick(click);
      mainButton().hideProgress?.();
      mainButton().hide();
    };
  }, [native, handler]);

  useEffect(() => {
    if (!native) return;
    if (!visible) {
      mainButton().hide();
      return;
    }
    mainButton().setParams({ text, is_active: !disabled && !progress, is_visible: true, ...(mainColors || {}) });
    if (progress) mainButton().showProgress?.(false);
    else mainButton().hideProgress?.();
  }, [native, text, disabled, progress, visible]);

  return native;
}

/** «Настройки» в меню Telegram (⋯) — дублирует шестерёнку в заголовке. */
export function useSettingsButton(onOpen) {
  const native = hasSettingsButton();
  const handler = useStableHandler(onOpen);
  const visible = Boolean(onOpen);

  useEffect(() => {
    if (!native) return undefined;
    const click = () => handler.current?.();
    settingsButton().onClick(click);
    return () => settingsButton().offClick(click);
  }, [native, handler]);

  useEffect(() => {
    if (!native) return;
    if (visible) settingsButton().show();
    else settingsButton().hide();
  }, [native, visible]);
}
