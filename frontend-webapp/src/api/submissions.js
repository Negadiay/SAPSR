import { apiFetch, fetchList } from './client';
import { downloadFile } from './download';

export const fetchTeachers = () => apiFetch('/teachers');

export const fetchSubmissions = () => fetchList('/submissions');

// Только файл и преподаватель (api-v1.md, раздел 7): вид работы, семестр и этап сервер определяет сам.
export const uploadSubmission = (file, teacherId) => {
  const formData = new FormData();
  formData.append('file', file);
  if (teacherId) formData.append('teacher_id', teacherId);
  return apiFetch('/upload', { method: 'POST', body: formData });
};

export const withdrawSubmission = (id) => apiFetch(`/submissions/${id}`, { method: 'DELETE' });

export const downloadReport = (id) => downloadFile(`/submissions/${id}/report`, `report_${id}.pdf`);

// Та же работа, но замечания стоят комментариями на своих местах (api-v1.md, раздел 17).
export const downloadCommented = (id) => downloadFile(`/submissions/${id}/commented`, `commented_${id}.pdf`);

// Комментарии преподавателя из размеченного файла и сам файл (api-v1.md, разделы 10–11).
export const fetchAnnotations = async (id) => {
  const res = await apiFetch(`/submissions/${id}/annotations`);
  return res.ok ? res.json() : null;
};

export const downloadReviewFile = (id) => downloadFile(`/submissions/${id}/review-file`, `review_${id}.pdf`);
