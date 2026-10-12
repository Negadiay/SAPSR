import { useState, useEffect } from 'react';
import { fetchTeacherSubmissions, fetchTeacherHistory } from '../../api/teacher';

const AUTO_REFRESH_INTERVAL_MS = 12000;

// Работы, ожидающие проверки, и история проверок с автообновлением.
export function useTeacherSubmissions({ active }) {
  const [pending, setPending] = useState([]);
  const [history, setHistory] = useState([]);

  const refreshPending = async () => {
    const data = await fetchTeacherSubmissions();
    if (data) setPending(data);
  };

  const refreshHistory = async () => {
    const data = await fetchTeacherHistory();
    if (data) setHistory(data);
  };

  const refreshAll = () => {
    refreshPending();
    refreshHistory();
  };

  useEffect(() => {
    if (!active) return undefined;
    refreshAll();
    const id = window.setInterval(refreshAll, AUTO_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  return { pending, history, refreshPending, refreshHistory, refreshAll };
}
