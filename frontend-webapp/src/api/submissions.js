import { apiFetch, fetchList } from './client';
import { downloadFile } from './download';

export const fetchTeachers = () => apiFetch('/teachers');

export const fetchSubmissions = () => fetchList('/submissions');

// profile — вид работы (api-v1.md, раздел 7); семестр и этап сервер определяет сам. Пустое поле не передаётся.
export const uploadSubmission = (file, teacherId, profile = {}) => {
  const formData = new FormData();
  formData.append('file', file);
  if (teacherId) formData.append('teacher_id', teacherId);
  if (profile.work_type) formData.append('work_type', profile.work_type);
  return apiFetch('/upload', { method: 'POST', body: formData });
};

export const withdrawSubmission = (id) => apiFetch(`/submissions/${id}`, { method: 'DELETE' });

export const downloadReport = (id) => downloadFile(`/submissions/${id}/report`, `report_${id}.pdf`);

// Комментарии преподавателя из размеченного файла и сам файл (api-v1.md, разделы 10–11).
export const fetchAnnotations = async (id) => {
  const res = await apiFetch(`/submissions/${id}/annotations`);
  return res.ok ? res.json() : null;
};

export const downloadReviewFile = (id) => downloadFile(`/submissions/${id}/review-file`, `review_${id}.pdf`);
