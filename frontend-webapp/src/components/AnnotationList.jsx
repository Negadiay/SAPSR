import Icon from './Icon';
import './AnnotationList.css';

const KIND_LABELS = {
  NOTE: 'Заметка',
  HIGHLIGHT: 'Выделение',
  UNDERLINE: 'Подчёркивание',
  STRIKEOUT: 'Зачёркивание',
  SQUIGGLY: 'Волнистая линия',
  FREE_TEXT: 'Надпись',
  INK: 'Пометка от руки',
  STAMP: 'Штамп',
  SHAPE: 'Фигура',
};

// Комментарии преподавателя из размеченного PDF: по страницам, ответы — под комментарием (api-v1.md, раздел 10).
export default function AnnotationList({ annotations }) {
  if (!annotations?.length) return null;
  const pages = [];
  annotations.forEach((a) => {
    const last = pages[pages.length - 1];
    if (last && last.page === a.page) last.items.push(a);
    else pages.push({ page: a.page, items: [a] });
  });

  return (
    <div className="annotation-list">
      {pages.map(({ page, items }) => (
        <div key={page} className="annotation-page">
          <div className="annotation-page-title">Страница {page}</div>
          {items.map((a) => (
            <div key={a.id} className={`annotation-item ${a.reply_to_id ? 'annotation-reply' : ''}`}>
              <span className="annotation-kind">
              {a.reply_to_id ? <span className="with-icon"><Icon name="reply" /><span>Ответ</span></span> : (KIND_LABELS[a.kind] || a.kind)}
            </span>
              {a.quoted_text && <blockquote className="annotation-quote">«{a.quoted_text}»</blockquote>}
              {a.comment && <div className="annotation-comment">{a.comment}</div>}
              {!a.comment && !a.quoted_text && <div className="annotation-comment annotation-muted">пометка на странице {page}</div>}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
