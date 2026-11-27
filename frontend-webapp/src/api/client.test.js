import { describe, it, expect, vi } from 'vitest';
import { apiFetch, postJson, fetchList } from './client';
import { mockApi, jsonResponse, stubTelegram } from '../test/mockApi';

describe('api/client', () => {
  it('подставляет initData Telegram в заголовок авторизации', async () => {
    const tg = stubTelegram({ userId: 7 });
    const api = mockApi({ 'GET /me': {} });
    await apiFetch('/me');
    expect(api.calls[0].headers).toEqual({ Authorization: tg.initData });
  });

  it('вне Telegram отправляет пустой заголовок авторизации', async () => {
    const api = mockApi({ 'GET /me': {} });
    await apiFetch('/me');
    expect(api.calls[0].headers).toEqual({ Authorization: '' });
  });

  it('postJson отправляет тело в JSON', async () => {
    stubTelegram();
    const api = mockApi({ 'POST /register': {} });
    await postJson('/register', { role: 'STUDENT' });
    expect(api.calls[0].body).toEqual({ role: 'STUDENT' });
    expect(api.calls[0].headers['Content-Type']).toBe('application/json');
  });

  describe('fetchList', () => {
    it('возвращает массив с сервера', async () => {
      mockApi({ 'GET /submissions': [{ id: 1 }] });
      expect(await fetchList('/submissions')).toEqual([{ id: 1 }]);
    });

    it('ответ не-массив превращает в пустой список', async () => {
      mockApi({ 'GET /submissions': { oops: true } });
      expect(await fetchList('/submissions')).toEqual([]);
    });

    it('при ошибке сервера возвращает null и пишет в консоль', async () => {
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      mockApi({ 'GET /submissions': jsonResponse({ error: 'boom' }, 500) });
      expect(await fetchList('/submissions')).toBeNull();
      expect(consoleError).toHaveBeenCalled();
      consoleError.mockRestore();
    });

    it('при сбое сети возвращает null', async () => {
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
      expect(await fetchList('/submissions')).toBeNull();
      consoleError.mockRestore();
    });
  });
});
