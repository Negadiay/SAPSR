import Icon from '../../components/Icon';

export default function NotesTab({ notes: notesState, onFind, addNoteRef }) {
  const {
    notes, noteInput, setNoteInput, editingNoteId,
    startNew, startEdit, cancelEdit, saveNew, saveEdit, requestDelete,
  } = notesState;

  return (
    <div className="tab-view">
      <h2 className="view-title">Заметки</h2>
      <button className="add-note-btn" ref={addNoteRef} onClick={startNew}>
        + Новая заметка
      </button>
      {editingNoteId === 'new' && (
        <div className="note-editor">
          <textarea className="note-textarea" value={noteInput}
            onChange={e => setNoteInput(e.target.value)}
            placeholder="Введите заметку..." autoFocus />
          <div className="note-editor-btns">
            <button className="submit-btn" onClick={saveNew}>Сохранить</button>
            <button className="secondary-btn" onClick={cancelEdit}>Отмена</button>
          </div>
        </div>
      )}
      <div className="notes-list">
        {notes.map(note => (
          <div key={note.id} className="note-card">
            {editingNoteId === note.id ? (
              <div className="note-editor">
                <textarea className="note-textarea" value={noteInput}
                  onChange={e => setNoteInput(e.target.value)} autoFocus />
                <div className="note-editor-btns">
                  <button className="submit-btn" onClick={() => saveEdit(note.id)}>Сохранить</button>
                  <button className="secondary-btn" onClick={cancelEdit}>Отмена</button>
                </div>
              </div>
            ) : (
              <>
                <p className="note-text">{note.text}</p>
                <div className="note-actions">
                  <button className="note-action-btn note-action-icon" title="Изменить заметку"
                    aria-label="Изменить заметку" onClick={() => startEdit(note)}><Icon name="edit" /></button>
                  <button className="note-action-btn note-action-delete note-action-icon" title="Удалить заметку"
                    aria-label="Удалить заметку" onClick={() => requestDelete(note.id)}><Icon name="trash" /></button>
                  <button className="note-action-btn note-action-find note-action-icon" title="Найти студента"
                    aria-label="Найти студента" onClick={() => onFind(note.text)}><Icon name="search" /></button>
                </div>
              </>
            )}
          </div>
        ))}
        {notes.length === 0 && !editingNoteId && (
          <p className="notif-empty">Нет заметок. Нажмите «+ Новая заметка»</p>
        )}
      </div>
    </div>
  );
}
