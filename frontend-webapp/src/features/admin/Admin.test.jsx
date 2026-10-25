import { describe, it, expect } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../../App';
import { mockApi, jsonResponse, stubTelegram } from '../../test/mockApi';

// Экран администратора (решение 11): то, что сервер определяет сам, видно строками и исправляется.

const AUTUMN_DATES = { 1: '2026-10-01', 2: '2026-10-29', 3: null, deadline: '2026-12-26' };

const CALENDAR = {
  today: '2026-10-24', academic_year: 2026, academic_year_title: '2026/2027',
  half: 'AUTUMN', half_by_date: 'AUTUMN', half_overridden: false, max_course: 4,
  group_digits: [3, 4, 5, 6],
  date_window: { from: '2026-08-01', to: '2027-01-31' },
  schedule: [1, 2, 3, 4].map((course) => ({
    course, semester: 2 * course - 1, dates: AUTUMN_DATES, current_stage: 2,
  })),
  groups: [
    { group: '321702', course: 4, course_by_number: 4, course_overridden: false, semester: 7, students: 2, teachers: 1 },
    { group: '621701', course: 1, course_by_number: 1, course_overridden: false, semester: 1, students: 1, teachers: 0 },
    { group: '421701', course: 2, course_by_number: 3, course_overridden: true, semester: 3, students: 1, teachers: 0 },
    { group: '451003', course: 3, course_by_number: 3, course_overridden: true, manual: true, semester: 5, students: 0, teachers: 0 },
    { group: '921701', course: null, course_by_number: null, course_overridden: false, semester: null, students: 1, teachers: 0 },
  ],
};

const SPRING = {
  ...CALENDAR, half: 'SPRING', half_overridden: true,
  groups: CALENDAR.groups.map((g) => (g.course ? { ...g, semester: 2 * g.course } : g)),
};

const TERESHKOVA = { id: 101, name: 'Терешкова Ана Викторовна', group: '321702' };
const IVANOV = { id: 102, name: 'Иванов Иван Иванович', group: '321702' };
const GRAKOVA = { id: 201, name: 'Гракова Наталья Викторовна', group: null };

const people = (group, students = [], teachers = []) => ({ group, students, teachers });

const adminRoutes = {
  'GET /me': { role: 'ADMIN', telegram_id: 9001, full_name: '', last_name: null, first_name: null, middle_name: null },
  'GET /admin/calendar': CALENDAR,
  'GET /admin/groups/321702/people': people('321702', [TERESHKOVA, IVANOV], [GRAKOVA]),
  'GET /admin/groups/421701/people': people('421701'),
  'GET /admin/groups/451003/people': people('451003'),
  'GET /admin/groups/621701/people': people('621701'),
  'GET /admin/groups/921701/people': people('921701'),
};

const start = (routes) => {
  stubTelegram();
  const api = mockApi(routes);
  const user = userEvent.setup();
  render(<App />);
  return { api, user };
};

const section = (title) => screen.getByText(title).closest('.admin-section');
const ready = () => screen.findByText('Учебный год 2026/2027');
const dialog = () => document.querySelector('.dialog');
const openSettings = (user) => user.click(screen.getByTitle('Настройки'));

describe('администратор', () => {
  it('видит год, семестр, сроки по курсам и группы по курсам', async () => {
    start(adminRoutes);
    expect(await screen.findByRole('heading', { name: 'Управление' })).toBeInTheDocument();
    await ready();
    expect(screen.getByLabelText('Семестр')).toHaveValue('AUTO');
    expect(screen.getByRole('option', { name: 'По дате (осенний)' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['1 курс', '2 курс', '3 курс', '4 курс']);
    expect(screen.getByText(/^Сейчас: 2-я опроцентовка, 29\.10/)).toBeInTheDocument();

    const groups = section('Группы');
    const fourth = within(groups).getByText('4 курс · 7 семестр').closest('.admin-course');
    expect(within(fourth).getByRole('button', { name: '321702' })).toBeInTheDocument();
    const second = within(groups).getByText('2 курс · 3 семестр').closest('.admin-course');
    expect(within(second).getByRole('img', { name: 'курс задан вручную' })).toBeInTheDocument();
    const unknown = within(groups).getByText('Курс не определён').closest('.admin-course');
    expect(within(unknown).getByRole('button', { name: '921701' })).toBeInTheDocument();
    // Работ у администратора нет: в заголовке только справка и настройки.
    expect(screen.getByTitle('Справка')).toBeInTheDocument();
    expect(screen.queryByTitle('Заметки')).toBeNull();
  });

  it('весенний семестр сдвигает курсы вперёд по году, а не назад', async () => {
    const { api, user } = start({
      ...adminRoutes,
      'PUT /admin/calendar/half': ({ body }) => (body.half === 'SPRING' ? SPRING : CALENDAR),
    });
    await ready();

    await user.selectOptions(screen.getByLabelText('Семестр'), 'SPRING');
    await waitFor(() => expect(api.find('PUT', '/admin/calendar/half')).toHaveLength(1));
    expect(api.find('PUT', '/admin/calendar/half')[0].body).toEqual({ half: 'SPRING' });
    expect(await screen.findByText('Задан вручную')).toBeInTheDocument();
    expect(screen.getByText('4 курс · 8 семестр')).toBeInTheDocument();
    expect(screen.getByText('1 курс · 2 семестр')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Семестр'), 'AUTO');
    await waitFor(() => expect(api.find('PUT', '/admin/calendar/half')).toHaveLength(2));
    expect(api.find('PUT', '/admin/calendar/half')[1].body).toEqual({ half: 'AUTO' });
    await waitFor(() => expect(screen.queryByText('Задан вручную')).toBeNull());
  });

  it('сроки сохраняются для выбранного курса; поля дат — в пределах семестра', async () => {
    const { api, user } = start({ ...adminRoutes, 'PUT /admin/calendar/schedule': CALENDAR });
    await ready();
    await user.click(screen.getByRole('tab', { name: '3 курс' }));
    expect(screen.getByRole('tab', { name: '3 курс' })).toHaveAttribute('aria-selected', 'true');

    const save = screen.getByRole('button', { name: 'Сохранить' });
    expect(save).toBeDisabled();
    const deadline = screen.getByLabelText('Сдача');
    expect(deadline).toHaveAttribute('min', '2026-08-01');
    expect(deadline).toHaveAttribute('max', '2027-01-31');
    await user.clear(deadline);
    await user.type(deadline, '2026-12-20');
    await user.click(save);

    await waitFor(() => expect(api.find('PUT', '/admin/calendar/schedule')).toHaveLength(1));
    expect(api.find('PUT', '/admin/calendar/schedule')[0].body).toEqual({
      courses: [3], dates: { 1: '2026-10-01', 2: '2026-10-29', 3: null, deadline: '2026-12-20' },
    });
  });

  it('«Всем курсам» — только после подтверждения', async () => {
    const { api, user } = start({ ...adminRoutes, 'PUT /admin/calendar/schedule': CALENDAR });
    await ready();

    await user.click(screen.getByRole('button', { name: 'Для всех курсов' }));
    expect(screen.getByText('Сроки для всех курсов?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(api.find('PUT', '/admin/calendar/schedule')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Для всех курсов' }));
    await user.click(screen.getByRole('button', { name: 'Заменить' }));
    await waitFor(() => expect(api.find('PUT', '/admin/calendar/schedule')).toHaveLength(1));
    expect(api.find('PUT', '/admin/calendar/schedule')[0].body.courses).toEqual([1, 2, 3, 4]);
  });

  it('курс группы меняется сразу и возвращается к номеру', async () => {
    const { api, user } = start({
      ...adminRoutes,
      'PUT /admin/groups/621701/course': CALENDAR,
      'DELETE /admin/groups/421701/course': CALENDAR,
    });
    await ready();

    await user.click(screen.getByRole('button', { name: /421701/ }));
    expect(screen.getByText('Группа 421701')).toBeInTheDocument();
    expect(screen.getByLabelText('Курс')).toHaveValue('2');
    expect(screen.getByRole('option', { name: 'По номеру (3)' })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Курс'), '');
    await waitFor(() => expect(api.find('DELETE', '/admin/groups/421701/course')).toHaveLength(1));

    await user.click(screen.getByRole('button', { name: '621701' }));
    await user.selectOptions(screen.getByLabelText('Курс'), '3');
    await waitFor(() => expect(api.find('PUT', '/admin/groups/621701/course')).toHaveLength(1));
    expect(api.find('PUT', '/admin/groups/621701/course')[0].body).toEqual({ course: 3 });

    // Группа не из текущих курсов (первая цифра 9): курс по ней не задать, но люди видны.
    await user.click(screen.getByRole('button', { name: '921701' }));
    expect(screen.getByText('Не из текущих курсов')).toBeInTheDocument();
    expect(screen.queryByLabelText('Курс')).toBeNull();
  });

  it('в группе видны студенты и преподаватели; опечатку в имени и группу исправляет без базы', async () => {
    const { api, user } = start({
      ...adminRoutes,
      'PUT /admin/users/101': { ...CALENDAR, people: people('321702', [IVANOV], [GRAKOVA]) },
    });
    await ready();
    await user.click(screen.getByRole('button', { name: '321702' }));

    expect(await screen.findByText('Терешкова Ана Викторовна')).toBeInTheDocument();
    expect(screen.getByText('Студенты · 2')).toBeInTheDocument();
    expect(screen.getByText('Гракова Наталья Викторовна')).toBeInTheDocument();
    // Преподавателя — только посмотреть; назначить администратором — только из правки, не из списка.
    expect(screen.queryByRole('button', { name: 'Изменить: Гракова Наталья Викторовна' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Сделать администратором/ })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Изменить: Терешкова Ана Викторовна' }));
    const name = screen.getByLabelText('ФИО');
    await user.clear(name);
    await user.type(name, 'Терешкова Анна Викторовна');
    const group = screen.getByLabelText('Группа');
    await user.clear(group);
    await user.type(group, '0');
    expect(group).toHaveValue('');
    expect(screen.getByText('Первая цифра — 3, 4, 5 или 6')).toBeInTheDocument();
    await user.type(group, '321701');
    await user.click(within(name.closest('li')).getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(api.find('PUT', '/admin/users/101')).toHaveLength(1));
    expect(api.find('PUT', '/admin/users/101')[0].body).toEqual({ name: 'Терешкова Анна Викторовна', group: '321701' });
    await waitFor(() => expect(screen.queryByLabelText('ФИО')).toBeNull());
    expect(screen.queryByText('Терешкова Ана Викторовна')).toBeNull();
    expect(screen.getByText('Студенты · 1')).toBeInTheDocument();
  });

  it('ошибка сохранения видна, а форма остаётся открытой', async () => {
    const { user } = start({
      ...adminRoutes,
      'PUT /admin/users/102': jsonResponse({ code: 'VALIDATION_ERROR', error: 'ФИО — с заглавной буквы' }, 400),
    });
    await ready();
    await user.click(screen.getByRole('button', { name: '321702' }));
    await user.click(await screen.findByRole('button', { name: 'Изменить: Иванов Иван Иванович' }));

    const name = screen.getByLabelText('ФИО');
    await user.clear(name);
    await user.type(name, 'иванов И.И.');
    await user.click(within(name.closest('li')).getByRole('button', { name: 'Сохранить' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ФИО — с заглавной буквы');
    expect(screen.getByLabelText('ФИО')).toHaveValue('иванов И.И.');
  });

  it('назначает студента администратором из правки и только после предупреждения об удалении данных', async () => {
    let students = [TERESHKOVA, IVANOV];
    const { api, user } = start({
      ...adminRoutes,
      'GET /admin/groups/321702/people': () => people('321702', students, [GRAKOVA]),
      'POST /admin/users/101/admin': () => { students = [IVANOV]; return CALENDAR; },
    });
    await ready();
    await user.click(screen.getByRole('button', { name: '321702' }));
    await user.click(await screen.findByRole('button', { name: 'Изменить: Терешкова Ана Викторовна' }));
    await user.click(screen.getByRole('button', { name: 'Сделать администратором' }));
    expect(screen.getByText('«Терешкова Ана Викторовна» станет администратором. '
      + 'Работы и учебные данные этого аккаунта удалятся безвозвратно.')).toBeInTheDocument();
    await user.click(within(dialog()).getByRole('button', { name: 'Отмена' }));
    expect(api.find('POST', '/admin/users/101/admin')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Сделать администратором' }));
    await user.click(screen.getByRole('button', { name: 'Назначить' }));
    await waitFor(() => expect(api.find('POST', '/admin/users/101/admin')).toHaveLength(1));
    await waitFor(() => expect(screen.queryByText('Терешкова Ана Викторовна')).toBeNull());
    expect(screen.getByText('Иванов Иван Иванович')).toBeInTheDocument();
  });

  it('новая группа — в свёрнутом разделе; первая цифра — только из возможных в этом году', async () => {
    const { api, user } = start({ ...adminRoutes, 'PUT /admin/groups/561003/course': CALENDAR });
    await ready();
    expect(screen.queryByLabelText('Номер группы')).toBeNull();
    await user.click(screen.getByRole('button', { name: /Новая группа/ }));
    const add = screen.getByRole('button', { name: 'Добавить' });
    const number = screen.getByLabelText('Номер группы');

    await user.type(number, '0');
    expect(number).toHaveValue('');
    expect(screen.getByText('Первая цифра — 3, 4, 5 или 6')).toBeInTheDocument();
    await user.type(number, '45a1003');
    expect(number).toHaveValue('451003');
    expect(screen.queryByText('Первая цифра — 3, 4, 5 или 6')).toBeNull();
    // Такая группа уже есть — курс ей меняют в её карточке, а не «добавлением».
    await user.selectOptions(screen.getByLabelText('Курс новой группы'), '3');
    expect(screen.getByText('Группа уже есть — курс меняется в ней.')).toBeInTheDocument();
    expect(add).toBeDisabled();

    await user.clear(number);
    await user.type(number, '561003');
    await user.click(add);
    await waitFor(() => expect(api.find('PUT', '/admin/groups/561003/course')).toHaveLength(1));
    expect(api.find('PUT', '/admin/groups/561003/course')[0].body).toEqual({ course: 3 });
  });

  it('удаляет пустую группу, добавленную вручную, после подтверждения', async () => {
    const { api, user } = start({ ...adminRoutes, 'DELETE /admin/groups/451003': CALENDAR });
    await ready();
    // У группы с людьми удаления нет.
    await user.click(screen.getByRole('button', { name: '321702' }));
    expect(screen.queryByRole('button', { name: 'Удалить группу' })).toBeNull();

    await user.click(screen.getByRole('button', { name: /451003/ }));
    // Курс у такой группы есть только записью администратора — «по номеру» её бы убрало, этого пункта нет.
    expect(screen.queryByRole('option', { name: /По номеру/ })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Удалить группу' }));
    expect(screen.getByText('Удалить группу 451003?')).toBeInTheDocument();
    await user.click(within(dialog()).getByRole('button', { name: 'Отмена' }));
    expect(api.find('DELETE', '/admin/groups/451003')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Удалить группу' }));
    await user.click(within(dialog()).getByRole('button', { name: 'Удалить' }));
    await waitFor(() => expect(api.find('DELETE', '/admin/groups/451003')).toHaveLength(1));
    expect(screen.queryByText('Группа 451003')).toBeNull();
  });

  it('разделы сворачиваются, и это запоминается', async () => {
    const { user } = start(adminRoutes);
    await ready();
    const head = screen.getByRole('button', { name: /Сроки/ });
    expect(head).toHaveAttribute('aria-expanded', 'true');
    await user.click(head);
    expect(head).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('tab', { name: '1 курс' })).toBeNull();

    // В свёрнутом разделе семестра — коротко, какой он сейчас.
    await user.click(screen.getByRole('button', { name: /Учебный год/ }));
    expect(screen.getByText('осенний')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('sapsr_admin_sections'))).toEqual({ schedule: false, half: false });
  });

  it('показывает ошибку сервера', async () => {
    start({ ...adminRoutes, 'GET /admin/calendar': jsonResponse({ code: 'FORBIDDEN', error: 'Раздел только для администратора' }, 403) });
    expect(await screen.findByRole('alert')).toHaveTextContent('Раздел только для администратора');
  });

  it('роль администратора в приложении не сменить', async () => {
    const { user } = start(adminRoutes);
    await ready();
    await openSettings(user);
    expect(screen.queryByRole('button', { name: 'Сменить роль' })).toBeNull();
  });

  it('в настройках — «Администратор», даже без имени, и без лишних пояснений', async () => {
    const { user } = start(adminRoutes);
    await ready();
    await openSettings(user);
    expect(screen.getByText('Администратор')).toBeInTheDocument();
    expect(screen.queryByText(/баз[еы] данных/)).toBeNull();
  });
});
