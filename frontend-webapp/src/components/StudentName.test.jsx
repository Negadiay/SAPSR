import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StudentName from './StudentName';
import { teacherKey } from '../features/student/useUpload';

describe('тёзки у преподавателя', () => {
  it('у тёзки — номер в скобках и подпись «тёзка»; без тёзок — просто ФИО', () => {
    const student = { full_name: 'Иванов Иван Иванович', has_namesake: true, namesake_number: 2 };
    const { rerender } = render(<p><StudentName submission={{ student, student_name: 'Иванов И.И.' }} /></p>);
    expect(screen.getByText(/Иванов Иван Иванович \(2\)/)).toBeInTheDocument();
    expect(screen.getByText('тёзка')).toHaveAttribute('title', 'В группе есть студент с таким же ФИО');
    rerender(<p><StudentName submission={{ student: { ...student, has_namesake: false, namesake_number: null } }} /></p>);
    expect(screen.queryByText('тёзка')).toBeNull();
    rerender(<p><StudentName submission={{ student_name: 'Терешкова А.В.' }} /></p>);
    expect(screen.getByText('Терешкова А.В.')).toBeInTheDocument();
  });

  it('преподаватель в форме сдачи — по ключу, а не по Telegram ID', () => {
    expect(teacherKey({ id: 'p-petrov', full_name: 'Петров' })).toBe('p-petrov');
    expect(teacherKey({ telegram_id: 7 })).toBe('7');
  });
});
