const byNewest = (a, b) => new Date(b?.created_at || 0) - new Date(a?.created_at || 0) || (b?.id || 0) - (a?.id || 0);

// Версии одной работы связаны parent_id (решение 9). Группа — цепочка версий, items — от новой к старой.
export const groupVersions = (submissions) => {
  const byId = new Map(submissions.map((s) => [s.id, s]));
  const hasNewer = new Set(submissions.filter((s) => s.parent_id != null && byId.has(s.parent_id)).map((s) => s.parent_id));
  return submissions
    .filter((s) => !hasNewer.has(s.id))
    .map((latest) => {
      const items = [];
      const seen = new Set();
      for (let s = latest; s && !seen.has(s.id); s = s.parent_id != null ? byId.get(s.parent_id) : null) {
        seen.add(s.id);
        items.push(s);
      }
      return { key: latest.id, latest, items };
    })
    .sort((a, b) => byNewest(a.latest, b.latest));
};

// Цепочка, в которой есть работа id: чтобы на экране работы показать её версии.
export const chainOf = (submissions, id) => groupVersions(submissions).find((g) => g.items.some((s) => s.id === id));
