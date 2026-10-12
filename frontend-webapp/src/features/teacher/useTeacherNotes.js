import { useState, useEffect } from 'react';

const loadNotes = (storageKey) => {
  try { return JSON.parse(localStorage.getItem(storageKey) || '[]'); } catch { return []; }
};

// Заметки преподавателя: хранятся в localStorage устройства.
export function useTeacherNotes({ userRole, currentUserId }) {
  const [notes, setNotes]                     = useState([]);
  const [noteInput, setNoteInput]             = useState('');
  // id редактируемой заметки, 'new' — создаётся новая
  const [editingNoteId, setEditingNoteId]     = useState(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState(null);

  const storageKey = `sapsr_notes_${currentUserId || 'teacher'}`;

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- перечитываем хранилище при смене пользователя
    setNotes(userRole === 'teacher' ? loadNotes(storageKey) : []);
  }, [userRole, storageKey]);

  const saveNotes = (next) => {
    setNotes(next);
    localStorage.setItem(storageKey, JSON.stringify(next));
  };

  const closeEditor = () => {
    setNoteInput('');
    setEditingNoteId(null);
  };

  const startNew = () => { setNoteInput(''); setEditingNoteId('new'); };
  const startEdit = (note) => { setEditingNoteId(note.id); setNoteInput(note.text); };
  const cancelEdit = () => setEditingNoteId(null);

  const saveNew = () => {
    const text = noteInput.trim();
    if (!text) return;
    saveNotes([...notes, { id: Date.now(), text }]);
    closeEditor();
  };

  const saveEdit = (id) => {
    const text = noteInput.trim();
    if (!text) return;
    saveNotes(notes.map(n => n.id === id ? { ...n, text } : n));
    closeEditor();
  };

  const requestDelete = (id) => setDeleteConfirmId(id);
  const cancelDelete = () => setDeleteConfirmId(null);

  const confirmDelete = () => {
    saveNotes(notes.filter(n => n.id !== deleteConfirmId));
    setDeleteConfirmId(null);
  };

  return {
    notes, noteInput, setNoteInput, editingNoteId,
    startNew, startEdit, cancelEdit, saveNew, saveEdit,
    deleteConfirmId, requestDelete, cancelDelete, confirmDelete,
  };
}
