import { useTeacherSubmissions } from './useTeacherSubmissions';
import { useReviewQueue } from './useReviewQueue';
import { useTeacherNotes } from './useTeacherNotes';
import { searchQueryFromNote } from './noteSearch';

// Всё состояние кабинета преподавателя; живёт выше экранов, чтобы не теряться при переходах.
export function useTeacherWorkspace({ active, userRole, currentUserId, onOpenQueue }) {
  const submissions = useTeacherSubmissions({ active });
  const queue = useReviewQueue({ submissions });
  const notes = useTeacherNotes({ userRole, currentUserId });

  // Кнопка поиска в заметке: открыть очередь с поиском по упомянутому студенту
  const findFromNote = (text) => {
    queue.setSegment('pending');
    queue.setSearch(searchQueryFromNote(text));
    onOpenQueue();
  };

  return { submissions, queue, notes, findFromNote };
}
