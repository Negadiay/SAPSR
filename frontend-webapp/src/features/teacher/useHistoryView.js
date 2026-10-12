import { useState } from 'react';
import { groupByFileName } from '../../lib/submissions';

// История проверок, сгруппированная по названию файла; одна группа может быть развёрнута.
export function useHistoryView({ submissions }) {
  const [expandedKey, setExpandedKey] = useState(null);

  const toggleExpanded = (key) => setExpandedKey(expandedKey === key ? null : key);

  return {
    groups: groupByFileName(submissions.history),
    refresh: submissions.refreshHistory,
    expandedKey, toggleExpanded,
  };
}
