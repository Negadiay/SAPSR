import { useEffect, useState } from 'react';
import AnnotationList from '../../components/AnnotationList';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import { downloadReviewFile, fetchAnnotations } from '../../api/submissions';

// Комментарии преподавателя из файла с пометками: по страницам, файл можно скачать.
// download — показать кнопку файла здесь; на экране работы она в карточке решения преподавателя.
export default function TeacherComments({ submission, download = true }) {
  const [state, setState] = useState({ loading: true, annotations: null, error: '' });

  useEffect(() => {
    let cancelled = false;
    fetchAnnotations(submission.id)
      .then((data) => {
        if (cancelled) return;
        setState(data
          ? { loading: false, annotations: data.annotations || [], error: '' }
          : { loading: false, annotations: null, error: 'Не удалось загрузить комментарии.' });
      })
      .catch(() => { if (!cancelled) setState({ loading: false, annotations: null, error: 'Нет связи с сервером.' }); });
    return () => { cancelled = true; };
  }, [submission.id]);

  return (
    <section className="block">
      <h3 className="section-title">Комментарии преподавателя</h3>
      {state.loading && <StatusLine tone="pending">Загрузка…</StatusLine>}
      {state.error && <StatusLine tone="error">{state.error}</StatusLine>}
      {state.annotations?.length === 0 && <p className="hint">Все пометки — в файле.</p>}
      {state.annotations?.length > 0 && <AnnotationList annotations={state.annotations} />}
      {download && (
        <button type="button" className="btn btn-secondary btn-block" onClick={() => downloadReviewFile(submission.id)}>
          <Icon name="download" /><span>Скачать файл с пометками</span>
        </button>
      )}
    </section>
  );
}
