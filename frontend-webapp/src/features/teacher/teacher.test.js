import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { searchQueryFromNote } from './noteSearch';
import { useTeacherNotes } from './useTeacherNotes';

describe('searchQueryFromNote', () => {
  it('берёт фамилию и номер группы', () => {
    expect(searchQueryFromNote('Спросить Кузнецова из 321701 про титульный лист')).toBe('Кузнецова 321701');
  });

  it('предпочитает слово с окончанием фамилии первому заглавному', () => {
    expect(searchQueryFromNote('Сегодня Петренко обещал исправить')).toBe('Петренко');
  });

  it('пропускает служебные слова в начале предложения', () => {
    expect(searchQueryFromNote('Нужно напомнить Сидорову')).toBe('Сидорову');
  });

  it('без фамилии возвращает только группу', () => {
    expect(searchQueryFromNote('проверить группу 321702')).toBe('321702');
  });

  it('для заметки без имён и групп возвращает пустую строку', () => {
    expect(searchQueryFromNote('купить маркеры')).toBe('');
  });
});

describe('useTeacherNotes', () => {
  const teacher = { userRole: 'teacher', currentUserId: '42' };
  const storageKey = 'sapsr_notes_42';
  const stored = () => JSON.parse(localStorage.getItem(storageKey));

  const addNote = (result, text) => {
    act(() => result.current.startNew());
    act(() => result.current.setNoteInput(text));
    act(() => result.current.saveNew());
  };

  it('создаёт заметку и закрывает редактор', () => {
    const { result } = renderHook(() => useTeacherNotes(teacher));
    addNote(result, '  Первая  ');
    expect(result.current.notes.map(n => n.text)).toEqual(['Первая']);
    expect(result.current.editingNoteId).toBeNull();
    expect(result.current.noteInput).toBe('');
    expect(stored().map(n => n.text)).toEqual(['Первая']);
  });

  it('пустую заметку не сохраняет и оставляет редактор открытым', () => {
    const { result } = renderHook(() => useTeacherNotes(teacher));
    addNote(result, '   ');
    expect(result.current.notes).toEqual([]);
    expect(result.current.editingNoteId).toBe('new');
  });

  it('изменяет существующую заметку', () => {
    const { result } = renderHook(() => useTeacherNotes(teacher));
    addNote(result, 'Черновик');
    const note = result.current.notes[0];
    act(() => result.current.startEdit(note));
    expect(result.current.noteInput).toBe('Черновик');
    act(() => result.current.setNoteInput('Готово'));
    act(() => result.current.saveEdit(note.id));
    expect(stored()).toEqual([{ id: note.id, text: 'Готово' }]);
  });

  it('удаляет заметку только после подтверждения', () => {
    const { result } = renderHook(() => useTeacherNotes(teacher));
    addNote(result, 'Удалить меня');
    const { id } = result.current.notes[0];
    act(() => result.current.requestDelete(id));
    expect(result.current.notes).toHaveLength(1);
    act(() => result.current.cancelDelete());
    expect(result.current.notes).toHaveLength(1);
    act(() => result.current.requestDelete(id));
    act(() => result.current.confirmDelete());
    expect(result.current.notes).toEqual([]);
    expect(result.current.deleteConfirmId).toBeNull();
    expect(stored()).toEqual([]);
  });

  it('загружает заметки своего пользователя', () => {
    localStorage.setItem(storageKey, JSON.stringify([{ id: 1, text: 'Моя' }]));
    localStorage.setItem('sapsr_notes_7', JSON.stringify([{ id: 2, text: 'Чужая' }]));
    const { result } = renderHook(() => useTeacherNotes(teacher));
    expect(result.current.notes).toEqual([{ id: 1, text: 'Моя' }]);
  });
});
