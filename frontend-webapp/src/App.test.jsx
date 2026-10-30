import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import { resetLaunchScreen } from './navigation/useNavigator';
import { mockApi, jsonResponse, stubTelegram } from './test/mockApi';

const ME = {
  role: 'STUDENT', telegram_id: 42, full_name: 'Иванов И.И. (гр. 321702)', last_name: 'Иванов',
  group: '321702', course: 4, semester: 7, stage: 2, deadline: '2026-12-26',
};

const TEACHERS = [
  { telegram_id: 7, full_name: 'Петров Пётр Петрович' },
  { telegram_id: 8, full_name: 'Сидоров Семён Семёнович' },
];

const FINDINGS = JSON.stringify([
  { severity: 'major', category: 'format', origin: 'symbolic', message: 'Левое поле 15 мм вместо 30.', page: '2–8',
    found: 'Так на 7 стр.', fix: 'Установите левое поле 30 мм.' },
  { severity: 'major', category: 'content', origin: 'hybrid', needs_teacher_review: true, page: 5,
    message: '«Языковая модель»: в разных разделах описано по-разному.' },
]);

// Цепочка версий: 5 — новая версия работы 4, которую вернули на доработку; 3 — другая работа.
const SUBMISSIONS = [
  { id: 5, parent_id: 4, version: 2, file_name: 'Курсовая v2.pdf', status: 'PROCESSING', content_status: 'PENDING',
    teacher_id: 7, teacher_name: 'Петров Пётр Петрович', work_type: 'course_project', created_at: '2026-10-21T10:00:00' },
  { id: 4, version: 1, file_name: 'Курсовая.pdf', status: 'REJECTED', content_status: 'DONE', format_errors: FINDINGS,
    teacher_verdict: 'REVISION', has_review_file: true, review_annotations: 1, teacher_id: 7,
    teacher_name: 'Петров Пётр Петрович', work_type: 'course_project', created_at: '2026-10-20T10:00:00' },
  { id: 3, version: 1, file_name: 'Практика.pdf', status: 'SUCCESS', content_status: 'DONE', format_errors: '[]',
    teacher_id: 8, created_at: '2026-10-01T10:00:00' },
];

const studentRoutes = {
  'GET /me': ME,
  'GET /teachers': TEACHERS,
  'GET /submissions': SUBMISSIONS,
  'GET /submissions/4/annotations': { annotations: [
    { id: 1, page: 3, kind: 'HIGHLIGHT', quoted_text: 'Система принимает DOCX', comment: 'Только PDF' },
  ] },
};

const start = (routes, telegram, userOptions) => {
  const tg = stubTelegram(telegram);
  const api = mockApi(routes);
  const user = userEvent.setup(userOptions);
  render(<App />);
  return { api, user, tg };
};

const heading = (name) => screen.findByRole('heading', { name });
const pdf = (name = 'Курсовая.pdf', size = 1000) => new File(['%PDF-1.7'.padEnd(size, ' ')], name, { type: 'application/pdf' });

afterEach(() => {
  window.history.replaceState({}, '', '/');
  resetLaunchScreen();
});

describe('вход', () => {
  it('незарегистрированному предлагает выбрать роль', async () => {
    start({ 'GET /me': { role: 'NONE' } });
    expect(screen.getByText('Загрузка…')).toBeInTheDocument();
    expect(await heading('Выберите роль')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Войти' })).toBeNull();
  });

  it('при недоступном сервере тоже показывает выбор роли', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    stubTelegram();
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    render(<App />);
    expect(await heading('Выберите роль')).toBeInTheDocument();
  });

  it('зарегистрированного студента сразу ведёт к его работам', async () => {
    start(studentRoutes);
    expect(await heading('Мои работы')).toBeInTheDocument();
    expect(screen.getByTitle('Обучение')).toBeInTheDocument();
    expect(screen.getByTitle('Настройки')).toBeInTheDocument();
  });

  it('регистрация студента: ошибка формата — после ухода из поля, а не после отправки', async () => {
    const { api, user } = start({
      'GET /me': { role: 'NONE' },
      'POST /register': { status: 'OK' },
      'GET /teachers': [], 'GET /submissions': [],
    });
    await user.click(await screen.findByRole('button', { name: /Студент/ }));
    const field = screen.getByLabelText('ФИО и группа');
    const submit = screen.getByRole('button', { name: 'Зарегистрироваться' });
    expect(submit).toBeDisabled();

    await user.type(field, 'Иванов И, 321702');
    await user.tab();
    expect(screen.getByText('Пример: Иванов И.И., 321702')).toBeInTheDocument();
    expect(field).toHaveAttribute('aria-invalid', 'true');

    await user.clear(field);
    await user.type(field, 'Иванов И.И., 321702');
    await user.click(submit);
    await waitFor(() => expect(api.find('POST', '/register')).toHaveLength(1));
    expect(api.find('POST', '/register')[0].body).toEqual({ role: 'STUDENT', full_name: 'Иванов И.И. (гр. 321702)' });
    expect(await heading('Мои работы')).toBeInTheDocument();
    // Первый вход — обучение начинается само.
    expect(await screen.findByRole('dialog', { name: 'Это SAPSR' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Пропустить' }));
    expect(localStorage.getItem('sapsr_onboarded_student')).toBe('1');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('ошибку сервера при регистрации показывает его словами', async () => {
    const { user } = start({
      'GET /me': { role: 'NONE' },
      'POST /register': jsonResponse({ error: 'Группы 321799 нет в ИИС БГУИР. Проверьте номер.' }, 400),
    });
    await user.click(await screen.findByRole('button', { name: /Студент/ }));
    await user.type(screen.getByLabelText('ФИО и группа'), 'Иванов И.И., 321799');
    await user.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Группы 321799 нет в ИИС БГУИР');
  });

  it('регистрация преподавателя: подсказка домена, код из письма', async () => {
    const { api, user } = start({
      'GET /me': { role: 'NONE' },
      'POST /register/send-code': { status: 'OK', expires_in_seconds: 300 },
      'POST /register': { status: 'OK' },
      'GET /teacher/submissions': [], 'GET /teacher/history': [],
    });
    await user.click(await screen.findByRole('button', { name: /Преподаватель/ }));
    await user.type(screen.getByLabelText('Рабочая почта'), 'petrov');
    await user.click(screen.getByRole('button', { name: 'Дописать @bsuir.by' }));
    expect(screen.getByLabelText('Рабочая почта')).toHaveValue('petrov@bsuir.by');
    await user.click(screen.getByRole('button', { name: 'Получить код' }));

    expect(await heading('Код из письма')).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: 'Подтвердить' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText('Код из письма'), '12a3456');
    expect(screen.getByLabelText('Код из письма')).toHaveValue('123456');
    await user.click(confirm);
    await waitFor(() => expect(api.find('POST', '/register')).toHaveLength(1));
    expect(api.find('POST', '/register')[0].body).toMatchObject({ role: 'TEACHER', email: 'petrov@bsuir.by', code: '123456' });
    expect(await heading('Работы')).toBeInTheDocument();
  });

  it('ссылка из бота открывает нужную работу', async () => {
    window.history.replaceState({}, '', '/?open=work-4');
    start(studentRoutes);
    expect(await screen.findByText('Курсовая.pdf')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Работа' })).toBeInTheDocument();
  });
});

describe('студент: работы', () => {
  it('версии одной работы — одной карточкой, состояние словами', async () => {
    start(studentRoutes);
    await heading('Мои работы');
    expect(await screen.findByText('Курсовая v2.pdf')).toBeInTheDocument();
    expect(screen.queryByText('Курсовая.pdf')).toBeNull();
    expect(screen.getByText('Проверяется содержание')).toBeInTheDocument();
    expect(screen.getByText(/^Версия 2/)).toBeInTheDocument();
    expect(screen.getByText('4 курс · 7 семестр')).toBeInTheDocument();
    expect(screen.getByText(/к 2-й опроцентовке · сдача до/)).toBeInTheDocument();
  });

  it('без работ — подсказка, что делать', async () => {
    start({ ...studentRoutes, 'GET /submissions': [] });
    expect(await screen.findByText('Работ пока нет.')).toBeInTheDocument();
  });

  it('у новой версии видно, что комментарии преподавателя — в прошлой', async () => {
    const { user } = start(studentRoutes);
    await user.click(await screen.findByRole('button', { name: /Курсовая v2\.pdf/ }));
    expect(await heading('Работа')).toBeInTheDocument();
    expect(screen.getByText('Курсовой проект · версия 2 · 21.10.2026, 10:00')).toBeInTheDocument();
    expect(screen.getByText('Преподаватель: Петров Пётр Петрович')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Комментарии преподавателя — в версии 1/ }));
    expect(await screen.findByText('Только PDF')).toBeInTheDocument();
    expect(screen.getByText('Вернул на доработку')).toBeInTheDocument();
    expect(screen.getByText('Ошибок: 1')).toBeInTheDocument();
    expect(screen.getByText('Левое поле 15 мм вместо 30.')).toBeInTheDocument();
    expect(screen.getByText('ошибка · стр. 2–8')).toBeInTheDocument();
    // Содержательное — без уровня «ошибка»: сдачу оно не блокирует.
    expect(screen.getByText('стр. 5 · нейросеть и правила · решит преподаватель')).toBeInTheDocument();

    await user.click(screen.getAllByRole('button', { name: 'Подробнее' })[0]);
    expect(screen.getByText('Установите левое поле 30 мм.')).toBeInTheDocument();
  });

  it('отчёт PDF скачивается с экрана работы', async () => {
    const { api, user } = start({ ...studentRoutes, 'GET /submissions/3/report': new Response('pdf') });
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    await user.click(await screen.findByRole('button', { name: /Практика\.pdf/ }));
    await user.click(screen.getByRole('button', { name: 'Отчёт PDF' }));
    await waitFor(() => expect(api.find('GET', '/submissions/3/report')).toHaveLength(1));
  });

  it('работу отзывают после подтверждения', async () => {
    let list = SUBMISSIONS;
    const { api, user } = start({
      ...studentRoutes,
      'GET /submissions': () => list,
      'DELETE /submissions/3': () => { list = SUBMISSIONS.slice(0, 2); return { status: 'OK' }; },
    });
    await user.click(await screen.findByRole('button', { name: /Практика\.pdf/ }));
    await user.click(screen.getByRole('button', { name: 'Отозвать' }));
    expect(screen.getByText('Преподаватель её больше не увидит.')).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Отмена' }));
    expect(api.find('DELETE', '/submissions/3')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Отозвать' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Отозвать' }));
    expect(await heading('Мои работы')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Практика.pdf')).toBeNull());
  });
});

describe('студент: сдача работы', () => {
  it('преподавателя не выбирает за студента; проверяет файл до отправки; после — экран новой работы', async () => {
    let list = [];
    const { api, user } = start({
      ...studentRoutes,
      'GET /submissions': () => list,
      'POST /upload': () => {
        list = [{ id: 9, file_name: 'Курсовая.pdf', status: 'QUEUED', created_at: '2026-10-25T10:00:00', version: 1 }];
        return { status: 'SUCCESS', db_id: 9 };
      },
    // Выбор файлов в Telegram не всегда учитывает accept — файл не PDF проверяется до отправки.
    }, undefined, { applyAccept: false });
    await user.click(await screen.findByRole('button', { name: 'Сдать работу' }));
    expect(await heading('Сдать работу')).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: 'Отправить на проверку' });
    await waitFor(() => expect(screen.getAllByRole('option', { name: /Петров/ })).toHaveLength(1));
    expect(screen.getByLabelText('Преподаватель')).toHaveValue('');

    expect(screen.getByRole('option', { name: 'Выберите преподавателя' })).toBeInTheDocument();
    // Вид работы не спрашивается: сервер определяет его по семестру и титульному листу.
    expect(screen.queryByLabelText('Вид работы')).toBeNull();

    await user.selectOptions(screen.getByLabelText('Преподаватель'), '7');
    // Выбрав преподавателя, к «никому» не вернуться: подсказки в списке больше нет.
    expect(screen.queryByRole('option', { name: 'Выберите преподавателя' })).toBeNull();
    await user.upload(screen.getByLabelText(/Выберите PDF/), new File(['x'], 'work.docx', { type: 'application/msword' }));
    expect(screen.getByText('Нужен файл PDF.')).toBeInTheDocument();
    expect(submit).toBeDisabled();

    await user.upload(screen.getByLabelText(/Выберите PDF|нажмите, чтобы заменить/), pdf());
    expect(screen.getByText('Курсовая.pdf')).toBeInTheDocument();
    await user.click(submit);

    await waitFor(() => expect(api.find('POST', '/upload')).toHaveLength(1));
    const form = api.find('POST', '/upload')[0].body;
    expect(form.get('teacher_id')).toBe('7');
    expect(form.has('work_type')).toBe(false);
    expect(await heading('Работа')).toBeInTheDocument();
    expect(screen.getByText('В очереди')).toBeInTheDocument();
    expect(localStorage.getItem('sapsr_last_teacher_id')).toBe('7');
  });

  it('единственный преподаватель выбирается сам; ошибка сервера — словами', async () => {
    const { user } = start({
      ...studentRoutes,
      'GET /teachers': [TEACHERS[0]],
      'POST /upload': jsonResponse({ error: 'Файл пустой.' }, 400),
    });
    await user.click(await screen.findByRole('button', { name: 'Сдать работу' }));
    await waitFor(() => expect(screen.getByLabelText('Преподаватель')).toHaveValue('7'));
    expect(screen.queryByRole('option', { name: 'Выберите преподавателя' })).toBeNull();
    await user.upload(screen.getByLabelText(/Выберите PDF/), pdf());
    await user.click(screen.getByRole('button', { name: 'Отправить на проверку' }));
    expect(await screen.findByText('Файл пустой.')).toBeInTheDocument();
  });

  it('нет преподавателей группы — сказано, к кому обратиться', async () => {
    const { user } = start({ ...studentRoutes, 'GET /teachers': [] });
    await user.click(await screen.findByRole('button', { name: 'Сдать работу' }));
    expect(await screen.findByText(/Для группы 321702 нет преподавателей/)).toBeInTheDocument();
  });

  it('новая версия — с тем же преподавателем', async () => {
    const { user } = start({ ...studentRoutes, 'GET /submissions': [SUBMISSIONS[1]] });
    await user.click(await screen.findByRole('button', { name: /Курсовая\.pdf/ }));
    await user.click(screen.getByRole('button', { name: 'Загрузить новую версию' }));
    expect(await heading('Новая версия')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Преподаватель')).toHaveValue('7'));
  });

  it('у старой версии загрузить новую нельзя — только у последней', async () => {
    const { user } = start(studentRoutes);
    await user.click(await screen.findByRole('button', { name: /Курсовая v2\.pdf/ }));
    await user.click(screen.getByRole('button', { name: /Комментарии преподавателя — в версии 1/ }));
    await screen.findByText('Только PDF');
    expect(screen.queryByRole('button', { name: 'Загрузить новую версию' })).toBeNull();
  });
});

describe('Telegram: родные кнопки', () => {
  it('главная кнопка — «Сдать работу», «Назад» и «Настройки» — в Telegram', async () => {
    const { tg } = start(studentRoutes, { native: true, platform: 'android' });
    await heading('Мои работы');
    await waitFor(() => expect(tg.MainButton.params.text).toBe('Сдать работу'));
    // Своей кнопки нет — её заменяет родная.
    expect(screen.queryByRole('button', { name: 'Сдать работу' })).toBeNull();
    expect(tg.BackButton.visible).toBe(false);

    act(() => tg.MainButton.click());
    expect(await heading('Сдать работу')).toBeInTheDocument();
    expect(tg.BackButton.visible).toBe(true);
    expect(screen.queryByRole('button', { name: 'Назад' })).toBeNull();
    await waitFor(() => expect(tg.MainButton.params.text).toBe('Отправить на проверку'));
    expect(tg.MainButton.params.is_active).toBe(false);

    act(() => tg.BackButton.click());
    expect(await heading('Мои работы')).toBeInTheDocument();
    act(() => tg.SettingsButton.click());
    expect(await heading('Настройки')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Как в Telegram' })).toBeChecked();
  });

  it('вне Telegram — своя стрелка «Назад»', async () => {
    const { user } = start(studentRoutes);
    await user.click(await screen.findByRole('button', { name: 'Сдать работу' }));
    await user.click(screen.getByRole('button', { name: 'Назад' }));
    expect(await heading('Мои работы')).toBeInTheDocument();
  });
});

describe('смена роли', () => {
  it('после подтверждения — снова выбор роли, прежнюю можно вернуть кнопкой «Войти»', async () => {
    const { user } = start(studentRoutes);
    await heading('Мои работы');
    await user.click(screen.getByTitle('Настройки'));
    await user.click(screen.getByRole('button', { name: 'Сменить роль' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Сменить' }));
    expect(await heading('Выберите роль')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Войти' }));
    expect(await heading('Мои работы')).toBeInTheDocument();
  });
});
