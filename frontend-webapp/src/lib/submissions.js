const getBaseFileName = (name = '') => name.replace(/\.[^.]+$/, '').trim().toLowerCase() || name;

const byNewest = (a, b) => new Date(b?.created_at || 0) - new Date(a?.created_at || 0);

// Версии одной работы объединяются по названию файла; items — от новой к старой.
export const groupByFileName = (submissions) => {
  const groups = new Map();
  submissions.forEach((submission) => {
    const key = getBaseFileName(submission.file_name);
    const group = groups.get(key) || { key, fileName: submission.file_name, items: [] };
    group.items.push(submission);
    groups.set(key, group);
  });
  return [...groups.values()].map((group) => {
    const items = [...group.items].sort(byNewest);
    return { ...group, items, latest: items[0] };
  }).sort((a, b) => byNewest(a.latest, b.latest));
};
