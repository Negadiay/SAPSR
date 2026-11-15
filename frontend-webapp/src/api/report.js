import { apiFetch, putJson } from './client';

// Отчёт v2 работы с отметками преподавателя и сравнением версий (api-v1.md, раздел 19).
// null — отчёта нет или он не загрузился: экран покажет замечания прежним списком.
export const fetchReportV2 = async (id) => {
  try {
    const res = await apiFetch(`/submissions/${id}/report-v2`);
    return res.ok ? await res.json() : null;
  } catch {
    return null;
  }
};

// Отметка преподавателя у замечания: 'correct' | 'incorrect' (api-v1.md, раздел 20). true — сохранено.
export const saveFindingFeedback = async (id, findingId, verdict) => {
  try {
    return (await putJson(`/teacher/submissions/${id}/findings/${encodeURIComponent(findingId)}/feedback`, { verdict })).ok;
  } catch {
    return false;
  }
};

export const deleteFindingFeedback = async (id, findingId) => {
  try {
    const res = await apiFetch(`/teacher/submissions/${id}/findings/${encodeURIComponent(findingId)}/feedback`,
      { method: 'DELETE' });
    return res.ok;
  } catch {
    return false;
  }
};
