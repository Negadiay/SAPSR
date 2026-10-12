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

export const getTabs = (role) => (role === 'teacher' ? TEACHER_TABS : STUDENT_TABS);
