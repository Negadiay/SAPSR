import { useStudentWorks } from './useStudentWorks';
import { useUpload } from './useUpload';

// Всё состояние кабинета студента; живёт выше вкладок, чтобы не теряться при переключении.
export function useStudentWorkspace({ active }) {
  const works = useStudentWorks({ active });
  const upload = useUpload({ active, onUploaded: works.refresh });
  return { works, upload };
}
