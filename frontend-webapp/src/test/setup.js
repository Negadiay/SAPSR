import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// Новые версии Node подставляют собственный localStorage, который без флага запуска не работает
// и перекрывает хранилище jsdom. В таком случае заменяем его хранилищем в памяти.
if (typeof globalThis.localStorage?.setItem !== 'function') {
  const data = new Map();
  const memoryStorage = {
    getItem: (key) => (data.has(String(key)) ? data.get(String(key)) : null),
    setItem: (key, value) => { data.set(String(key), String(value)); },
    removeItem: (key) => { data.delete(String(key)); },
    clear: () => { data.clear(); },
    key: (index) => [...data.keys()][index] ?? null,
    get length() { return data.size; },
  };
  Object.defineProperty(globalThis, 'localStorage', { value: memoryStorage, configurable: true });
  Object.defineProperty(window, 'localStorage', { value: memoryStorage, configurable: true });
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete window.Telegram;
});
