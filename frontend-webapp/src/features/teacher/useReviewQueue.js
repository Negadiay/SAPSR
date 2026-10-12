import { useState } from 'react';
import { sendVerdict } from '../../api/teacher';
import { readError } from '../../api/client';
import { matchesSearch } from '../../lib/fuzzySearch';
import { notifyHaptic } from '../../telegram/webApp';

// Очередь на проверку: поиск, раскрытая карточка, форма «на доработку», вердикт.
export function useReviewQueue({ submissions, templates }) {
  const [search, setSearch]                   = useState('');
  const [expandedId, setExpandedId]           = useState(null);
  const [revisionId, setRevisionId]           = useState(null);
  const [revisionComment, setRevisionComment] = useState('');
  const [verdictLoading, setVerdictLoading]   = useState(false);

  const filtered = submissions.pending.filter(
    (s) => matchesSearch(search, [s.student_name, s.file_name, s.created_at])
  );

  const toggleExpanded = (id) => setExpandedId(expandedId === id ? null : id);

  const openRevision = (id) => { setRevisionId(id); setRevisionComment(''); };
  const cancelRevision = () => { setRevisionId(null); setRevisionComment(''); };

  const submitVerdict = async (id, verdict, comment = '') => {
    setVerdictLoading(true);
    try {
      const res = await sendVerdict(id, verdict, comment);
      if (res.ok) {
        if (verdict === 'REVISION') templates.remember(comment);
        notifyHaptic('success');
        setRevisionId(null); setRevisionComment(''); setExpandedId(null);
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
    revisionId, revisionComment, setRevisionComment, openRevision, cancelRevision,
    suggestions: templates.suggest(revisionComment),
    verdictLoading, submitVerdict,
  };
}
