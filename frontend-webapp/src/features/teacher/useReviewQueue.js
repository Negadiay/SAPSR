import { useState } from 'react';
import { sendVerdict, uploadReviewFile } from '../../api/teacher';
import { readError } from '../../api/client';
import { matchesSearch } from '../../lib/fuzzySearch';
import { notifyHaptic } from '../../telegram/webApp';

// Очередь на проверку: поиск, раскрытая карточка, PDF с пометками преподавателя, вердикт.
// Замечания студенту — только в файле с пометками: «на доработку» без него не отправить.
export function useReviewQueue({ submissions }) {
  const [search, setSearch]                 = useState('');
  const [expandedId, setExpandedId]         = useState(null);
  const [verdictLoading, setVerdictLoading] = useState(false);
  // Файл с пометками по работам: { [id]: { uploading, error, review_file_id, annotations, warnings } }.
  const [reviews, setReviews]               = useState({});

  const filtered = submissions.pending.filter(
    (s) => matchesSearch(search, [s.student_name, s.file_name, s.created_at])
  );

  const toggleExpanded = (id) => setExpandedId(expandedId === id ? null : id);

  const setReview = (id, value) => setReviews((current) => ({ ...current, [id]: value }));

  const attachReviewFile = async (id, file) => {
    if (!file) return;
    setReview(id, { uploading: true });
    try {
      const res = await uploadReviewFile(id, file);
      if (res.ok) {
        setReview(id, await res.json());
        notifyHaptic('success');
      } else {
        const err = await readError(res);
        setReview(id, { error: err.error || err.message || `Ошибка ${res.status}` });
        notifyHaptic('error');
      }
    } catch { setReview(id, { error: 'Сервер недоступен' }); }
  };

  // Сколько комментариев в приложенном файле: из ответа на загрузку или из списка работ после обновления.
  const annotationCount = (submission) =>
    reviews[submission.id]?.annotations?.length ?? submission.review_annotations ?? 0;

  const canSendRevision = (submission) => annotationCount(submission) > 0;

  const submitVerdict = async (id, verdict) => {
    setVerdictLoading(true);
    try {
      const res = await sendVerdict(id, verdict, '', reviews[id]?.review_file_id);
      if (res.ok) {
        notifyHaptic('success');
        setExpandedId(null);
        setReviews((current) => { const next = { ...current }; delete next[id]; return next; });
        submissions.refreshAll();
      } else {
        const err = await readError(res);
        alert(err.error || 'Ошибка');
      }
    } catch { alert('Сервер недоступен'); }
    finally { setVerdictLoading(false); }
  };

  return {
    pending: submissions.pending, filtered, refresh: submissions.refreshPending,
    search, setSearch,
    expandedId, toggleExpanded,
    reviews, attachReviewFile, annotationCount, canSendRevision,
    verdictLoading, submitVerdict,
  };
}
