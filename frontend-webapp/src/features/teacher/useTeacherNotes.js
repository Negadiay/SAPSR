import { useState, useEffect } from 'react';

const loadNotes = (storageKey) => {
  try { return JSON.parse(localStorage.getItem(storageKey) || '[]'); } catch { return []; }
};

// Заметки преподавателя: хранятся в localStorage устройства.
export function useTeacherNotes({ userRole, currentUserId }) {
  const [notes, setNotes]                 = useState([]);
  const [noteInput, setNoteInput]         = useState('');
  // id редактируемой заметки, 'new' — создаётся новая
  const [editingNoteId, setEditingNoteId] = useState(null);

  const storageKey = `sapsr_notes_${currentUserId || 'teacher'}`;

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- перечитываем хранилище при смене пользователя
    setNotes(userRole === 'teacher' ? loadNotes(storageKey) : []);
  }, [userRole, storageKey]);

  const saveNotes = (next) => {
    setNotes(next);
    try { localStorage.setItem(storageKey, JSON.stringify(next)); } catch { /* не запомнится — не страшно */ }
  };

  const closeEditor = () => {
    setNoteInput('');
    setEditingNoteId(null);
  };

  const startNew = () => { setNoteInput(''); setEditingNoteId('new'); };
  const startEdit = (note) => { setEditingNoteId(note.id); setNoteInput(note.text); };

  const saveNew = () => {
    const text = noteInput.trim();
    if (!text) return;
    saveNotes([{ id: Date.now(), text }, ...notes]);
    closeEditor();
  };

  const saveEdit = (id) => {
    const text = noteInput.trim();
    if (!text) return;
    saveNotes(notes.map((n) => (n.id === id ? { ...n, text } : n)));
    closeEditor();
  };

  const remove = (id) => saveNotes(notes.filter((n) => n.id !== id));

  return {
    notes, noteInput, setNoteInput, editingNoteId,
    startNew, startEdit, cancelEdit: closeEditor, saveNew, saveEdit, remove,
  };
}
