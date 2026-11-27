import { useState, useEffect } from 'react';
import { fetchSubmissions, withdrawSubmission } from '../../api/submissions';
import { readError } from '../../api/client';
import { isInProgress } from '../../lib/status';

// Пока что-то проверяется, список обновляется чаще: итог появляется без кнопки «Обновить».
const BUSY_REFRESH_MS = 5000;
const IDLE_REFRESH_MS = 20000;

// Работы студента с автообновлением и отзыв работы.
export function useStudentWorks({ active }) {
  const [submissions, setSubmissions] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [withdrawing, setWithdrawing] = useState(null);
  const [error, setError] = useState('');

  const refresh = async () => {
    const data = await fetchSubmissions();
    if (data) setSubmissions(data);
    setLoaded(true);
    return data;
  };

  const busy = submissions.some(isInProgress);

  useEffect(() => {
    if (!active) return undefined;
    refresh();
    const id = window.setInterval(refresh, busy ? BUSY_REFRESH_MS : IDLE_REFRESH_MS);
    return () => window.clearInterval(id);
  }, [active, busy]);

  const withdraw = async (id) => {
    setWithdrawing(id);
    setError('');
    try {
      const res = await withdrawSubmission(id);
      if (!res.ok) {
        setError((await readError(res)).error || 'Не удалось отозвать работу.');
        return false;
      }
      await refresh();
      return true;
    } catch {
      setError('Нет связи с сервером.');
      return false;
    } finally { setWithdrawing(null); }
  };

  return { submissions, loaded, error, refresh, withdraw, withdrawing };
}
