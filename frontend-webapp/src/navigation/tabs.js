// --- Структура вкладок ---
// icon — имя иконки из components/Icon.jsx; label — подпись кнопки для программ чтения с экрана.
// tutorialRef — ключ элемента, который подсвечивает обучение (см. features/tutorial).
const STUDENT_TABS = [
  { id: 'upload',   icon: 'upload',   label: 'Загрузка' },
  { id: 'works',    icon: 'works',    label: 'Мои работы', tutorialRef: 'navNotif' },
  { id: 'settings', icon: 'settings', label: 'Настройки',  tutorialRef: 'navSettings' },
];

const TEACHER_TABS = [
  { id: 'queue',    icon: 'queue',    label: 'Работы',    badge: true },
  { id: 'history',  icon: 'history',  label: 'История',   tutorialRef: 'navHistory' },
  { id: 'notes',    icon: 'notes',    label: 'Заметки',   tutorialRef: 'navNotes' },
  { id: 'settings', icon: 'settings', label: 'Настройки', tutorialRef: 'navSettings' },
];

// Администратор работы не проверяет: у него только настройки календаря (решение 11).
const ADMIN_TABS = [
  { id: 'admin',    icon: 'admin',    label: 'Управление' },
  { id: 'settings', icon: 'settings', label: 'Настройки' },
];

export const getTabs = (role) => {
  if (role === 'teacher') return TEACHER_TABS;
  if (role === 'admin') return ADMIN_TABS;
  return STUDENT_TABS;
};
