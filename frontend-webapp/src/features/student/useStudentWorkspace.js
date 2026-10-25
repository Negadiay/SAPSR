import { useStudentWorks } from './useStudentWorks';

// Состояние кабинета студента живёт выше экранов, чтобы не теряться при переходах.
export function useStudentWorkspace({ active }) {
  const works = useStudentWorks({ active });
  return { works };
}
