import { useState } from 'react';
import AppHeader from '../../components/AppHeader';
import ConfirmDialog from '../../components/ConfirmDialog';
import Icon from '../../components/Icon';

function NoteEditor({ value, onChange, onSave, onCancel, placeholder }) {
  return (
    <div className="card note-editor">
      <textarea className="input note-textarea" value={value} onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder} aria-label="Текст заметки" autoFocus />
      <div className="actions">
        <button type="button" className="btn btn-primary" disabled={!value.trim()} onClick={onSave}>Сохранить</button>
        <button type="button" className="btn btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
    </div>
  );
}

// Заметки преподавателя на этом устройстве; лупа ищет работы студента, упомянутого в заметке.
export default function NotesScreen({ notes: notesState, header, onFind }) {
  const {
    notes, noteInput, setNoteInput, editingNoteId,
    startNew, startEdit, cancelEdit, saveNew, saveEdit,
  } = notesState;
  const [deleting, setDeleting] = useState(null);

  return (
    <div className="screen-body">
      <AppHeader title="Заметки" {...header} />
      <p className="hint">Хранятся на этом устройстве.</p>
      {editingNoteId === 'new' ? (
        <NoteEditor value={noteInput} onChange={setNoteInput} onSave={saveNew} onCancel={cancelEdit}
          placeholder="Например: Терешкова 321702 — проверить раздел 3" />
      ) : (
        <button type="button" className="btn btn-secondary btn-block" onClick={startNew}>
          <Icon name="plus" /><span>Новая заметка</span>
        </button>
      )}

      {notes.length === 0 && editingNoteId !== 'new' && <p className="empty">Заметок нет.</p>}

      <ul className="card-list">
        {notes.map((note) => (
          <li key={note.id}>
            {editingNoteId === note.id ? (
              <NoteEditor value={noteInput} onChange={setNoteInput} onSave={() => saveEdit(note.id)} onCancel={cancelEdit} />
            ) : (
              <div className="card note-card">
                <p className="note-text">{note.text}</p>
                <div className="note-actions">
                  <button type="button" className="icon-btn" onClick={() => onFind(note.text)}>
                    <Icon name="search" label="Найти работы студента" />
                  </button>
                  <button type="button" className="icon-btn" onClick={() => startEdit(note)}>
                    <Icon name="edit" label="Изменить заметку" />
                  </button>
                  <button type="button" className="icon-btn icon-btn-danger" onClick={() => setDeleting(note.id)}>
                    <Icon name="trash" label="Удалить заметку" />
                  </button>
                </div>
              </div>
            )}
          </li>
        ))}
      </ul>

      {deleting !== null && (
        <ConfirmDialog title="Удалить заметку?" confirmLabel="Удалить" danger
          onCancel={() => setDeleting(null)}
          onConfirm={() => { notesState.remove(deleting); setDeleting(null); }} />
      )}
    </div>
  );
}
