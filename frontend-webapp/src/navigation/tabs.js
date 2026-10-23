// --- Структура вкладок ---
// tutorialRef — ключ элемента, который подсвечивает обучение (см. features/tutorial).
const STUDENT_TABS = [
  { id: 'upload',   icon: '📁', label: 'Загрузка' },
  { id: 'works',    icon: '🔔', label: 'Работы',    tutorialRef: 'navNotif' },
  { id: 'settings', icon: '⚙️', label: 'Настройки', tutorialRef: 'navSettings' },
];

const TEACHER_TABS = [
  { id: 'queue',    icon: '📋', label: 'Работы',    badge: true },
  { id: 'history',  icon: '📚', label: 'История',   tutorialRef: 'navHistory' },
  { id: 'notes',    icon: '📝', label: 'Заметки',   tutorialRef: 'navNotes' },
  { id: 'settings', icon: '⚙️', label: 'Настройки', tutorialRef: 'navSettings' },
];

// Администратор работы не проверяет: у него только настройки календаря (решение 11).
const ADMIN_TABS = [
  { id: 'admin',    icon: '🛠️', label: 'Управление' },
  { id: 'settings', icon: '⚙️', label: 'Настройки' },
];

export const getTabs = (role) => {
  if (role === 'teacher') return TEACHER_TABS;
  if (role === 'admin') return ADMIN_TABS;
  return STUDENT_TABS;
};
