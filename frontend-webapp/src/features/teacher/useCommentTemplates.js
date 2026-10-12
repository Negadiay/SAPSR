import { useState, useEffect } from 'react';

const COMMENT_TEMPLATE_LIMIT = 20;
const COMMENT_SUGGESTION_LIMIT = 3;

const sortTemplates = (templates) => [...templates]
  .sort((a, b) => (b.count - a.count) || (b.lastUsed - a.lastUsed))
  .slice(0, COMMENT_TEMPLATE_LIMIT);

// Шаблоны комментариев «на доработку»: запоминаются в localStorage, чаще используемые — выше.
export function useCommentTemplates({ userRole, currentUserId }) {
  const [templates, setTemplates] = useState([]);

  const storageKey = `sapsr_revision_templates_${currentUserId || userRole || 'teacher'}`;

  /* eslint-disable react-hooks/set-state-in-effect -- перечитываем хранилище при смене пользователя */
  useEffect(() => {
    if (userRole !== 'teacher') {
      setTemplates([]);
      return;
    }
    try {
      const raw = localStorage.getItem(storageKey);
      setTemplates(raw ? sortTemplates(JSON.parse(raw)) : []);
    } catch {
      setTemplates([]);
    }
  }, [userRole, storageKey]);
  /* eslint-enable react-hooks/set-state-in-effect */

  const remember = (comment) => {
    const text = comment.trim();
    if (!text) return;
    const now = Date.now();
    const existing = templates.find(t => t.text === text);
    const next = existing
      ? templates.map(t => t.text === text ? { ...t, count: t.count + 1, lastUsed: now } : t)
      : [...templates, { text, count: 1, lastUsed: now }];
    const sorted = sortTemplates(next);
    setTemplates(sorted);
    localStorage.setItem(storageKey, JSON.stringify(sorted));
  };

  const suggest = (input) => {
    const query = input.trim().toLowerCase();
    return sortTemplates(templates)
      .filter(t => !query || t.text.toLowerCase().includes(query))
      .slice(0, COMMENT_SUGGESTION_LIMIT);
  };

  return { remember, suggest };
}
