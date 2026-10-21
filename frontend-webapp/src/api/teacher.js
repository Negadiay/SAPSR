import { apiFetch, fetchList, postJson } from './client';
import { downloadFile } from './download';

export const fetchTeacherSubmissions = () => fetchList('/teacher/submissions');

export const fetchTeacherHistory = () => fetchList('/teacher/history');

// review_file_id передаётся, только если преподаватель приложил размеченный файл (api-v1.md, раздел 9).
export const sendVerdict = (id, verdict, comment, reviewFileId) => postJson(
  `/teacher/submissions/${id}/verdict`,
  reviewFileId ? { verdict, comment, review_file_id: reviewFileId } : { verdict, comment },
);

export const downloadSubmissionPdf = (id) => downloadFile(`/teacher/submissions/${id}/pdf`, `submission_${id}.pdf`);

// PDF с комментариями преподавателя: ответ — извлечённые комментарии и предупреждения (api-v1.md, раздел 8).
export const uploadReviewFile = (id, file) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiFetch(`/teacher/submissions/${id}/review-file`, { method: 'POST', body: formData });
};
