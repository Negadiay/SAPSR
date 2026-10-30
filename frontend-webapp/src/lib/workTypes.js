// Виды работ (api-v1.md, раздел 7). Имя файла по форме «КП7_321702_Терешкова.pdf» собирает бэкенд
// (WorkFileName), когда преподаватель скачивает работу.
export const WORK_TYPES = [
  { value: 'course_work', label: 'Курсовая работа' },
  { value: 'course_project', label: 'Курсовой проект' },
  { value: 'diploma', label: 'Дипломная работа' },
];

export const workTypeLabel = (value) => WORK_TYPES.find((t) => t.value === value)?.label || '';
