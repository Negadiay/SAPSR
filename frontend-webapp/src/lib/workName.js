// Виды работ и шаблон имени файла: «КР7_321702_Терешкова.pdf» — вид, семестр, группа, фамилия.
// Тот же шаблон бэкенд подставляет в имя файла, когда преподаватель скачивает работу (backend: WorkFileName).
export const WORK_TYPES = [
  { value: 'course_work', label: 'Курсовая работа', prefix: 'КР' },
  { value: 'course_project', label: 'Курсовой проект', prefix: 'КП' },
  { value: 'diploma', label: 'Дипломная работа', prefix: 'ДР' },
];

// Неизвестные части остаются подсказкой в угловых скобках, чтобы было видно, что подставить.
export const workFileName = ({ workType, semester, group, lastName }) => {
  const type = WORK_TYPES.find((t) => t.value === workType);
  if (!type) return '';
  return `${type.prefix}${semester || '<семестр>'}_${group || '<группа>'}_${lastName || '<фамилия>'}.pdf`;
};
