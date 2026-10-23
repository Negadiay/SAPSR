import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../../App';
import { mockApi, jsonResponse, stubTelegram } from '../../test/mockApi';

// Экран администратора (решение 11): то, что сервер определяет сам, видно строками и исправляется.

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }) => children,
  motion: { div: ({ className, children }) => <div className={className}>{children}</div> },
}));

const CALENDAR = {
  today: '2026-10-23', academic_year: 2026, academic_year_title: '2026/2027',
  half: 'AUTUMN', half_by_date: 'AUTUMN', half_overridden: false,
  procentovki: [
    { stage: 1, date: '2026-10-01' }, { stage: 2, date: '2026-10-29' }, { stage: 3, date: null },
  ],
  current_stage: 2, max_course: 4,
  groups: [
    { group: '321702', course: 4, course_by_number: 4, course_overridden: false, semester: 7, students: 3, teachers: 1 },
    { group: '621701', course: 1, course_by_number: 1, course_overridden: false, semester: 1, students: 1, teachers: 0 },
    { group: '721701', course: 2, course_by_number: null, course_overridden: true, semester: 3, students: 1, teachers: 0 },
    { group: '921701', course: null, course_by_number: null, course_overridden: false, semester: null, students: 1, teachers: 0 },
  ],
};

const adminRoutes = {
  'GET /me': { role: 'ADMIN', telegram_id: 9001, full_name: '', last_name: null, first_name: null, middle_name: null },
  'GET /admin/calendar': CALENDAR,
};

const start = (routes) => {
  stubTelegram();
  const api = mockApi(routes);
  const user = userEvent.setup();
  render(<App />);
  return { api, user };
};

const section = (title) => screen.getByText(title).closest('.admin-section');

describe('администратор', () => {
  it('видит календарь строками: семестр, опроцентовки, группы по курсам', async () => {
    start(adminRoutes);
    expect(await screen.findByRole('heading', { name: 'Управление' })).toBeInTheDocument();
    expect(await screen.findByText('Учебный год 2026/2027')).toBeInTheDocument();
    expect(screen.getByLabelText('Семестр сейчас')).toHaveValue('AUTO');
    expect(screen.getByText('Сейчас работы проверяются к 2-й опроцентовке (29.10.2026).')).toBeInTheDocument();

    const groups = section('Курсы групп');
    const fourth = within(groups).getByText('4 курс · 7 семестр').closest('.admin-course');
    expect(within(fourth).getByRole('button', { name: '321702' })).toBeInTheDocument();
    const second = within(groups).getByText('2 курс · 3 семестр').closest('.admin-course');
    expect(within(second).getByRole('button', { name: /721701/ })).toHaveTextContent('✎');
    const unknown = within(groups).getByText('Курс не определён').closest('.admin-course');
    expect(within(unknown).getByRole('button', { name: '921701' })).toBeInTheDocument();
    // Работ у администратора нет — только две вкладки и без обучения.
    expect(within(document.querySelector('.bottom-nav')).getAllByRole('button')).toHaveLength(2);
    expect(screen.queryByTitle('Справка')).toBeNull();
  });

  it('меняет половину года и возвращает определение по дате', async () => {
    const { api, user } = start({
      ...adminRoutes,
      'PUT /admin/calendar/half': ({ body }) => ({
        ...CALENDAR, half: body.half === 'AUTO' ? 'AUTUMN' : body.half, half_overridden: body.half === 'SPRING',
      }),
    });
    await screen.findByText('Учебный год 2026/2027');

    await user.selectOptions(screen.getByLabelText('Семестр сейчас'), 'SPRING');
    await waitFor(() => expect(api.find('PUT', '/admin/calendar/half')).toHaveLength(1));
    expect(api.find('PUT', '/admin/calendar/half')[0].body).toEqual({ half: 'SPRING' });
    expect(await screen.findByText(/Задано вручную, по дате сейчас осенний/)).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Семестр сейчас'), 'AUTO');
    await waitFor(() => expect(api.find('PUT', '/admin/calendar/half')).toHaveLength(2));
    expect(api.find('PUT', '/admin/calendar/half')[1].body).toEqual({ half: 'AUTO' });
  });

  it('сохраняет даты опроцентовок, пустая дата — снята', async () => {
    const { api, user } = start({ ...adminRoutes, 'PUT /admin/calendar/procentovki': CALENDAR });
    await screen.findByText('Учебный год 2026/2027');
    const save = screen.getByRole('button', { name: 'Сохранить даты' });
    expect(save).toBeDisabled();

    const third = screen.getByLabelText('3-я опроцентовка');
    await user.clear(third);
    await user.type(third, '2026-11-26');
    await user.click(save);

    await waitFor(() => expect(api.find('PUT', '/admin/calendar/procentovki')).toHaveLength(1));
    expect(api.find('PUT', '/admin/calendar/procentovki')[0].body)
      .toEqual({ 1: '2026-10-01', 2: '2026-10-29', 3: '2026-11-26' });
  });

  it('исправляет курс группы и возвращает его к номеру', async () => {
    const { api, user } = start({
      ...adminRoutes,
      'PUT /admin/groups/921701/course': CALENDAR,
      'DELETE /admin/groups/721701/course': CALENDAR,
    });
    await screen.findByText('Учебный год 2026/2027');

    await user.click(screen.getByRole('button', { name: '921701' }));
    expect(screen.getByText('По номеру — не определяется. Студентов: 1, преподавателей: 0.')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Курс в этом учебном году'), '3');
    await user.click(screen.getByRole('button', { name: 'Сохранить курс' }));
    await waitFor(() => expect(api.find('PUT', '/admin/groups/921701/course')).toHaveLength(1));
    expect(api.find('PUT', '/admin/groups/921701/course')[0].body).toEqual({ course: 3 });

    await user.click(screen.getByRole('button', { name: /721701/ }));
    expect(screen.getByLabelText('Курс в этом учебном году')).toHaveValue('2');
    await user.selectOptions(screen.getByLabelText('Курс в этом учебном году'), '');
    await user.click(screen.getByRole('button', { name: 'Сохранить курс' }));
    await waitFor(() => expect(api.find('DELETE', '/admin/groups/721701/course')).toHaveLength(1));
  });

  it('задаёт курс группе, которой нет в списке', async () => {
    const { api, user } = start({ ...adminRoutes, 'PUT /admin/groups/451003/course': CALENDAR });
    await screen.findByText('Учебный год 2026/2027');
    const add = screen.getByRole('button', { name: 'Задать курс' });

    await user.type(screen.getByLabelText('Номер группы'), '45a1003');
    expect(screen.getByLabelText('Номер группы')).toHaveValue('451003');
    expect(add).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Курс новой группы'), '3');
    await user.click(add);

    await waitFor(() => expect(api.find('PUT', '/admin/groups/451003/course')).toHaveLength(1));
    expect(api.find('PUT', '/admin/groups/451003/course')[0].body).toEqual({ course: 3 });
  });

  it('показывает ошибку сервера', async () => {
    start({ ...adminRoutes, 'GET /admin/calendar': jsonResponse({ code: 'FORBIDDEN', error: 'Раздел только для администратора' }, 403) });
    expect(await screen.findByRole('alert')).toHaveTextContent('Раздел только для администратора');
  });

  it('после выхода входит снова — регистрироваться ему не нужно', async () => {
    const { user } = start(adminRoutes);
    await screen.findByText('Учебный год 2026/2027');
    await user.click(within(document.querySelector('.bottom-nav')).getByRole('button', { name: /⚙️/ }));
    await user.click(screen.getByRole('button', { name: 'Выйти из системы' }));

    await user.click(await screen.findByRole('button', { name: 'Войти как администратор' }));
    expect(await screen.findByRole('heading', { name: 'Управление' })).toBeInTheDocument();
  });

  it('в настройках — «Администратор», даже без имени', async () => {
    const { user } = start(adminRoutes);
    await screen.findByText('Учебный год 2026/2027');
    await user.click(within(document.querySelector('.bottom-nav')).getByRole('button', { name: /⚙️/ }));
    expect(screen.getByText('Администратор')).toBeInTheDocument();
    expect(screen.getByText('Роль назначена в базе данных')).toBeInTheDocument();
  });
});
