import { useState } from 'react';
import { sendVerdict, uploadReviewFile } from '../../api/teacher';
import { readError } from '../../api/client';
import { matchesSearch } from '../../lib/fuzzySearch';
import { notifyHaptic } from '../../telegram/webApp';

// Очередь преподавателя: «На проверке» и «Проверено», поиск, фильтр по группе, файл с пометками и решение.
// Замечания студенту — только в файле с пометками: «на доработку» без него не отправить.
export function useReviewQueue({ submissions }) {
  const [segment, setSegment] = useState('pending');
  const [search, setSearch]   = useState('');
  const [group, setGroup]     = useState('');
  // Файл с пометками по работам: { [id]: { uploading, error, review_file_id, annotations, warnings } }.
  const [reviews, setReviews] = useState({});
  const [deciding, setDeciding] = useState(false);
  const [verdictError, setVerdictError] = useState('');

  const list = segment === 'pending' ? submissions.pending : submissions.history;
  const groups = [...new Set(list.map((s) => s.student_group).filter(Boolean))].sort();
  const filtered = list.filter((s) => (!group || s.student_group === group)
    && matchesSearch(search, [s.student_name, s.student_group, s.file_name]));

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
        setReview(id, { error: err.error || 'Не удалось загрузить файл.' });
        notifyHaptic('error');
      }
    } catch { setReview(id, { error: 'Нет связи с сервером.' }); }
  };

  // Сколько комментариев в приложенном файле: из ответа на загрузку или из списка работ после обновления.
  const annotationCount = (s) => reviews[s.id]?.annotations?.length ?? s.review_annotations ?? 0;

  const canSendRevision = (s) => annotationCount(s) > 0;

  const decide = async (id, verdict) => {
    setDeciding(true);
    setVerdictError('');
    try {
      const res = await sendVerdict(id, verdict, reviews[id]?.review_file_id);
      if (!res.ok) {
        setVerdictError((await readError(res)).error || 'Не удалось сохранить решение.');
        notifyHaptic('error');
        return false;
      }
      notifyHaptic('success');
      setReviews((current) => { const next = { ...current }; delete next[id]; return next; });
      await submissions.refreshAll();
      return true;
    } catch {
      setVerdictError('Нет связи с сервером.');
      return false;
    } finally { setDeciding(false); }
  };

  const changeSegment = (next) => { setSegment(next); setGroup(''); };

  return {
    segment, setSegment: changeSegment, search, setSearch, group, setGroup, groups, filtered,
    reviews, attachReviewFile, annotationCount, canSendRevision,
    deciding, verdictError, clearVerdictError: () => setVerdictError(''), decide,
  };
}
