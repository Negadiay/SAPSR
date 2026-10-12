import { apiFetch, fetchList } from './client';
import { downloadFile } from './download';

export const fetchTeachers = () => apiFetch('/teachers');

export const fetchSubmissions = () => fetchList('/submissions');

export const uploadSubmission = (file, teacherId) => {
  const formData = new FormData();
  formData.append('file', file);
  if (teacherId) formData.append('teacher_id', teacherId);
  return apiFetch('/upload', { method: 'POST', body: formData });
};

export const withdrawSubmission = (id) => apiFetch(`/submissions/${id}`, { method: 'DELETE' });

export const downloadReport = (id) => downloadFile(`/submissions/${id}/report`, `report_${id}.pdf`);
