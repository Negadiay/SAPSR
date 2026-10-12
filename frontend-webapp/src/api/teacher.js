import { fetchList, postJson } from './client';
import { downloadFile } from './download';

export const fetchTeacherSubmissions = () => fetchList('/teacher/submissions');

export const fetchTeacherHistory = () => fetchList('/teacher/history');

export const sendVerdict = (id, verdict, comment) => postJson(`/teacher/submissions/${id}/verdict`, { verdict, comment });

export const downloadSubmissionPdf = (id) => downloadFile(`/teacher/submissions/${id}/pdf`, `submission_${id}.pdf`);
