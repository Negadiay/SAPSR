import { useState } from 'react';
import AnnotationList from '../../components/AnnotationList';
import { downloadReviewFile, fetchAnnotations } from '../../api/submissions';

// Комментарии преподавателя в файле работы: по нажатию загружаются по страницам, файл можно скачать.
export default function TeacherComments({ submission }) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState({ loading: false, annotations: null, error: '' });

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (!next || state.annotations) return;
    setState({ loading: true, annotations: null, error: '' });
    try {
      const data = await fetchAnnotations(submission.id);
      setState(data
        ? { loading: false, annotations: data.annotations || [], error: '' }
        : { loading: false, annotations: null, error: 'Не удалось загрузить комментарии' });
    } catch {
      setState({ loading: false, annotations: null, error: 'Сервер недоступен' });
    }
  };

  return (
    <div className="teacher-comments">
      <div className="teacher-comments-actions">
        <button type="button" className="teacher-comments-toggle" aria-expanded={open} onClick={toggle}>
          📝 Комментарии в файле: {submission.review_annotations ?? 0} {open ? '▲' : '▼'}
        </button>
        <button type="button" className="teacher-comments-download" onClick={() => downloadReviewFile(submission.id)}>
          Скачать файл с пометками
        </button>
      </div>
      {open && state.loading && <div className="teacher-comments-hint">⏳ Загрузка…</div>}
      {open && state.error && <div className="teacher-comments-hint">❌ {state.error}</div>}
      {open && state.annotations?.length === 0 && (
        <div className="teacher-comments-hint">Текстовых комментариев нет — пометки видны в файле.</div>
      )}
      {open && state.annotations && <AnnotationList annotations={state.annotations} />}
    </div>
  );
}
