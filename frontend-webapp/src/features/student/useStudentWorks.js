import { useState, useEffect } from 'react';
import { fetchSubmissions, withdrawSubmission } from '../../api/submissions';

const AUTO_REFRESH_INTERVAL_MS = 12000;

// Список работ студента с автообновлением и отзыв работы.
export function useStudentWorks({ active }) {
  const [submissions, setSubmissions] = useState([]);

  // Отзыв работы студентом
  const [withdrawing, setWithdrawing]             = useState(null);
  const [withdrawConfirmId, setWithdrawConfirmId] = useState(null);

  const refresh = async () => {
    const data = await fetchSubmissions();
    if (data) setSubmissions(data);
  };

  useEffect(() => {
    if (!active) return undefined;
    refresh();
    const id = window.setInterval(refresh, AUTO_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [active]);

  const requestWithdraw = (submissionId) => setWithdrawConfirmId(submissionId);
  const cancelWithdraw = () => setWithdrawConfirmId(null);

  const confirmWithdraw = async () => {
    const id = withdrawConfirmId;
    setWithdrawConfirmId(null);
    setWithdrawing(id);
    try {
      await withdrawSubmission(id);
      refresh();
    } finally { setWithdrawing(null); }
  };

  return {
    submissions, refresh,
    withdrawing, withdrawConfirmId, requestWithdraw, cancelWithdraw, confirmWithdraw,
  };
}
