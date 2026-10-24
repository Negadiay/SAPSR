import { useTeacherSubmissions } from './useTeacherSubmissions';
import { useReviewQueue } from './useReviewQueue';
import { useHistoryView } from './useHistoryView';
import { useTeacherNotes } from './useTeacherNotes';
import { searchQueryFromNote } from './noteSearch';

// Всё состояние кабинета преподавателя; живёт выше вкладок, чтобы не теряться при переключении.
export function useTeacherWorkspace({ active, userRole, currentUserId, onOpenQueue }) {
  const submissions = useTeacherSubmissions({ active });
  const queue = useReviewQueue({ submissions });
  const history = useHistoryView({ submissions });
  const notes = useTeacherNotes({ userRole, currentUserId });

  // Кнопка поиска в заметке: открыть очередь с поиском по упомянутому студенту
  const findFromNote = (text) => {
    queue.setSearch(searchQueryFromNote(text));
    onOpenQueue();
  };

  return { queue, history, notes, findFromNote };
}
