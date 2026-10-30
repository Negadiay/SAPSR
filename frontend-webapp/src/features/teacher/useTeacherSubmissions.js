import { useState, useEffect } from 'react';
import { fetchTeacherSubmissions, fetchTeacherHistory } from '../../api/teacher';

const BUSY_REFRESH_MS = 8000;
const IDLE_REFRESH_MS = 20000;

// Работы, ожидающие решения, и проверенные — с автообновлением; чаще, пока где-то проверяется содержание.
export function useTeacherSubmissions({ active }) {
  const [pending, setPending] = useState([]);
  const [history, setHistory] = useState([]);
  const [loaded, setLoaded] = useState(false);

  const refreshPending = async () => {
    const data = await fetchTeacherSubmissions();
    if (data) setPending(data);
    setLoaded(true);
  };

  const refreshHistory = async () => {
    const data = await fetchTeacherHistory();
    if (data) setHistory(data);
  };

  const refreshAll = () => Promise.all([refreshPending(), refreshHistory()]);

  const busy = pending.some((s) => s.content_status === 'PENDING' || s.content_status === 'LATE');

  useEffect(() => {
    if (!active) return undefined;
    refreshAll();
    const id = window.setInterval(refreshAll, busy ? BUSY_REFRESH_MS : IDLE_REFRESH_MS);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, busy]);

  return { pending, history, loaded, refreshAll };
}
