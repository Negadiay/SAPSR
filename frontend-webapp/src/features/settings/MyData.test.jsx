import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MyData from './MyData';
import StudentName from '../../components/StudentName';
import { teacherKey } from '../student/useUpload';
import { mockApi, jsonResponse, stubTelegram } from '../../test/mockApi';

const OLD = { role: 'STUDENT', full_name: 'Иванов И.И. (гр. 321702)', last_name: 'Иванов', first_name: null,
  middle_name: null, group: '321702', profile_incomplete: true, namesake_number: null };

describe('Мои данные', () => {
  it('аккаунту старой формы предлагает дополнить ФИО; группу не спрашивает', async () => {
    stubTelegram();
    const saved = { ...OLD, first_name: 'Иван', middle_name: 'Иванович', profile_incomplete: false, namesake_number: 2 };
    const api = mockApi({ 'PATCH /me': saved });
    const onSaved = vi.fn();
    const user = userEvent.setup();
    render(<MyData profile={OLD} onSaved={onSaved} />);

    expect(screen.getByText(/Укажите имя и отчество полностью/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Дополнить ФИО' }));
    expect(screen.getByLabelText('Фамилия')).toHaveValue('Иванов');
    expect(screen.queryByLabelText('Номер группы')).toBeNull();
    const save = screen.getByRole('button', { name: 'Сохранить' });
    expect(save).toBeDisabled();
    await user.type(screen.getByLabelText('Имя'), 'Иван');
    await user.type(screen.getByLabelText('Отчество'), 'Иванович');
    await user.click(save);

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved));
    expect(api.find('PATCH', '/me')[0].body).toEqual({ last_name: 'Иванов', first_name: 'Иван', middle_name: 'Иванович' });
    expect(screen.queryByLabelText('Имя')).toBeNull();
  });

  it('номер тёзки показывается, только если тёзка есть', () => {
    const { rerender } = render(<MyData profile={{ ...OLD, profile_incomplete: false, first_name: 'Иван', namesake_number: 2 }} />);
    expect(screen.getByText('Среди студентов группы с таким же ФИО вы — №2.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Исправить ФИО' })).toBeInTheDocument();
    rerender(<MyData profile={{ ...OLD, profile_incomplete: false, first_name: 'Иван' }} />);
    expect(screen.queryByText(/с таким же ФИО/)).toBeNull();
  });

  it('ошибку сервера показывает его словами и оставляет форму', async () => {
    stubTelegram();
    mockApi({ 'PATCH /me': jsonResponse({ code: 'VALIDATION_ERROR', field: 'group', message: 'Группу меняет администратор.' }, 400) });
    const user = userEvent.setup();
    render(<MyData profile={{ ...OLD, first_name: 'Иван', middle_name: 'Иванович', profile_incomplete: false }} />);
    await user.click(screen.getByRole('button', { name: 'Исправить ФИО' }));
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(await screen.findByText('Группу меняет администратор.')).toBeInTheDocument();
    expect(screen.getByLabelText('Имя')).toHaveValue('Иван');
  });
});

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
