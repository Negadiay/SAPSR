// Виды работ. prefix — начало имени файла по форме «КР7_321702_Терешкова.pdf» (решение 10): его подставляет
// бэкенд, когда преподаватель скачивает работу (backend: WorkFileName), студенту оно не подсказывается.
export const WORK_TYPES = [
  { value: 'course_work', label: 'Курсовая работа', prefix: 'КР' },
  { value: 'course_project', label: 'Курсовой проект', prefix: 'КП' },
  { value: 'diploma', label: 'Дипломная работа', prefix: 'ДР' },
];
