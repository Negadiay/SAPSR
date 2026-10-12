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
                  <button className="note-action-btn note-action-icon" title="Изменить"
                    onClick={() => startEdit(note)}>✏️</button>
                  <button className="note-action-btn note-action-delete note-action-icon" title="Удалить"
                    onClick={() => requestDelete(note.id)}>🗑</button>
                  <button className="note-action-btn note-action-find note-action-icon" title="Найти студента"
                    onClick={() => onFind(note.text)}>🔍</button>
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
