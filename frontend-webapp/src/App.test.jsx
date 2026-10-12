import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import { mockApi, jsonResponse, stubTelegram } from './test/mockApi';

// Анимации в jsdom не идут: экраны и вкладки сменяются сразу.
vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }) => children,
  motion: { div: ({ className, children }) => <div className={className}>{children}</div> },
}));

const TEACHERS = [
  { telegram_id: 7, full_name: 'Петров П.П.' },
  { telegram_id: 8, full_name: 'Сидоров С.С.' },
];

const STUDENT_SUBMISSIONS = [
  { id: 5, file_name: 'Kursach.pdf', status: 'SUCCESS', created_at: '2026-10-02T10:00:00Z' },
  { id: 4, file_name: 'kursach.pdf', status: 'SUCCESS', teacher_verdict: 'REVISION', teacher_comment: 'Поправить поля', created_at: '2026-10-01T10:00:00Z' },
  { id: 3, file_name: 'other.pdf', status: 'REJECTED', created_at: '2026-09-01T10:00:00Z' },
];

const TEACHER_QUEUE = [
  { id: 1, student_name: 'Иванов И.И. (гр. 321702)', file_name: 'kursach.pdf', created_at: '2026-10-01T10:00:00Z' },
  { id: 2, student_name: 'Кузнецова А.А. (гр. 321701)', file_name: 'zapiska.pdf', created_at: '2026-10-02T10:00:00Z' },
];

const TEACHER_HISTORY = [
  { id: 11, student_name: 'Иванов И.И. (гр. 321702)', file_name: 'old.pdf', teacher_verdict: 'APPROVED', created_at: '2026-09-02T10:00:00Z' },
  { id: 10, student_name: 'Иванов И.И. (гр. 321702)', file_name: 'old.pdf', teacher_verdict: 'REVISION', teacher_comment: 'Нет списка литературы', created_at: '2026-09-01T10:00:00Z' },
];

const studentRoutes = {
  'GET /me': { role: 'STUDENT', telegram_id: 42 },
  'GET /teachers': TEACHERS,
  'GET /submissions': STUDENT_SUBMISSIONS,
};

const teacherRoutes = {
  'GET /me': { role: 'TEACHER', telegram_id: 42 },
  'GET /teacher/submissions': TEACHER_QUEUE,
  'GET /teacher/history': TEACHER_HISTORY,
};

const start = (routes) => {
  stubTelegram();
  const api = mockApi(routes);
  const user = userEvent.setup();
  render(<App />);
  return { api, user };
};

const tabButton = (icon) => within(document.querySelector('.bottom-nav')).getByRole('button', { name: new RegExp(icon) });

describe('вход', () => {
  it('незарегистрированному пользователю предлагает выбрать роль', async () => {
    start({ 'GET /me': { role: 'NONE' } });
    expect(screen.getByText('Загрузка...')).toBeInTheDocument();
    expect(await screen.findByText('Выберите вашу роль в системе')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument();
  });

  it('при недоступном сервере тоже показывает выбор роли', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    stubTelegram();
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    render(<App />);
    expect(await screen.findByText('Выберите вашу роль в системе')).toBeInTheDocument();
  });

  it('зарегистрированного студента сразу ведёт на загрузку работы', async () => {
    start(studentRoutes);
    expect(await screen.findByRole('heading', { name: 'Загрузка работы' })).toBeInTheDocument();
    expect(document.querySelectorAll('.bottom-nav button')).toHaveLength(3);
  });

  it('зарегистрированного преподавателя сразу ведёт в очередь работ', async () => {
    start(teacherRoutes);
    expect(await screen.findByRole('heading', { name: 'Работы студентов' })).toBeInTheDocument();
    expect(document.querySelectorAll('.bottom-nav button')).toHaveLength(4);
  });
});

describe('регистрация студента', () => {
  const routes = { 'GET /me': { role: 'NONE' }, 'POST /register': {}, 'GET /teachers': TEACHERS, 'GET /submissions': [] };

  it('в поле проходят только кириллица, цифры, точки и запятая', async () => {
    const { user } = start(routes);
    await user.click(await screen.findByRole('button', { name: /Студент/ }));
    const input = screen.getByPlaceholderText('Иванов И.О., 321702');
    await user.type(input, 'Ivanov Иванов! И.И., 321702');
    expect(input).toHaveValue(' Иванов И.И., 321702');
  });

  it('неверный формат не отправляется на сервер', async () => {
    const { api, user } = start(routes);
    await user.click(await screen.findByRole('button', { name: /Студент/ }));
    await user.type(screen.getByPlaceholderText('Иванов И.О., 321702'), 'Иванов Иван 321702');
    await user.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    expect(screen.getByText('Формат: Иванов И.И., 123456')).toBeInTheDocument();
    expect(api.find('POST', '/register')).toHaveLength(0);
  });

  it('верные данные регистрируют, открывают кабинет и запускают обучение', async () => {
    const { api, user } = start(routes);
    await user.click(await screen.findByRole('button', { name: /Студент/ }));
    await user.type(screen.getByPlaceholderText('Иванов И.О., 321702'), 'Иванов И.И., 321702');
    await user.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    expect(await screen.findByRole('heading', { name: 'Загрузка работы' })).toBeInTheDocument();
    expect(api.find('POST', '/register')[0].body).toEqual({ role: 'STUDENT', full_name: 'Иванов И.И. (гр. 321702)' });
    expect(await screen.findByText(/Добро пожаловать в SAPSR/, {}, { timeout: 2000 })).toBeInTheDocument();
  });

  it('показывает ошибку сервера и остаётся на форме', async () => {
    const { user } = start({ ...routes, 'POST /register': jsonResponse({ error: 'Группа не найдена' }, 400) });
    await user.click(await screen.findByRole('button', { name: /Студент/ }));
    await user.type(screen.getByPlaceholderText('Иванов И.О., 321702'), 'Иванов И.И., 321702');
    await user.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    expect(await screen.findByText('Группа не найдена')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Зарегистрироваться' })).toBeEnabled();
  });
});

describe('регистрация преподавателя', () => {
  const routes = {
    'GET /me': { role: 'NONE' },
    'POST /register/send-code': { expires_in_seconds: 120 },
    'POST /register': {},
    'GET /teacher/submissions': [],
    'GET /teacher/history': [],
  };

  const openForm = async (user) => {
    await user.click(await screen.findByRole('button', { name: /Преподаватель/ }));
    return screen.getByPlaceholderText('ivanov@bsuir.by');
  };

  it('без почты код не запрашивается', async () => {
    const { api, user } = start(routes);
    await openForm(user);
    await user.click(screen.getByRole('button', { name: 'Отправить код' }));
    expect(screen.getByText('Введите email')).toBeInTheDocument();
    expect(api.find('POST', '/register/send-code')).toHaveLength(0);
  });

  it('почта приводится к нижнему регистру, после кода открывается кабинет', async () => {
    const { api, user } = start(routes);
    await user.type(await openForm(user), ' Ivanov@BSUIR.by ');
    await user.click(screen.getByRole('button', { name: 'Отправить код' }));

    expect(await screen.findByText('ivanov@bsuir.by')).toBeInTheDocument();
    expect(api.find('POST', '/register/send-code')[0].body).toEqual({ email: 'ivanov@bsuir.by' });
    expect(await screen.findByText(/Код действителен ещё [12]:\d\d/)).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText('6-значный код'), '123456');
    await user.click(screen.getByRole('button', { name: 'Подтвердить' }));

    expect(await screen.findByRole('heading', { name: 'Работы студентов' })).toBeInTheDocument();
    expect(api.find('POST', '/register')[0].body).toEqual({
      role: 'TEACHER', full_name: '', email: 'ivanov@bsuir.by', code: '123456',
    });
  });

  it('ответ «не найден в IIS» показывается понятным текстом', async () => {
    const { user } = start({
      ...routes,
      'POST /register/send-code': jsonResponse({ error: 'Преподаватель не найден в IIS' }, 404),
    });
    await user.type(await openForm(user), 'nobody@bsuir.by');
    await user.click(screen.getByRole('button', { name: 'Отправить код' }));
    expect(await screen.findByText('Почта не обнаружена в ИИС БГУИР')).toBeInTheDocument();
  });

  it('«Назад» с экрана кода возвращает к вводу почты', async () => {
    const { user } = start(routes);
    await user.type(await openForm(user), 'ivanov@bsuir.by');
    await user.click(screen.getByRole('button', { name: 'Отправить код' }));
    await screen.findByPlaceholderText('6-значный код');
    await user.click(screen.getByRole('button', { name: 'Назад' }));
    expect(screen.getByPlaceholderText('ivanov@bsuir.by')).toHaveValue('ivanov@bsuir.by');
  });
});

describe('кабинет студента', () => {
  it('отправляет выбранный файл выбранному преподавателю', async () => {
    const { api, user } = start({ ...studentRoutes, 'POST /upload': {} });
    const submit = await screen.findByRole('button', { name: 'Отправить' });
    await screen.findByRole('option', { name: 'Сидоров С.С.' });
    expect(submit).toBeDisabled();

    await user.selectOptions(screen.getByLabelText('Преподаватель'), '8');
    const file = new File(['%PDF'], 'моя работа.pdf', { type: 'application/pdf' });
    await user.upload(document.getElementById('file-upload'), file);
    expect(submit).toBeEnabled();
    await user.click(submit);

    expect(await screen.findByText('✅ Работа успешно отправлена!')).toBeInTheDocument();
    const { body } = api.find('POST', '/upload')[0];
    expect(body.get('file').name).toBe('моя работа.pdf');
    expect(body.get('teacher_id')).toBe('8');
    expect(localStorage.getItem('sapsr_last_teacher_id')).toBe('8');
  });

  it('слишком большой файл объясняется понятным текстом', async () => {
    const { user } = start({ ...studentRoutes, 'POST /upload': jsonResponse({}, 413) });
    const submit = await screen.findByRole('button', { name: 'Отправить' });
    await screen.findByRole('option', { name: 'Петров П.П.' });
    await user.upload(document.getElementById('file-upload'), new File(['%PDF'], 'big.pdf', { type: 'application/pdf' }));
    await user.click(submit);
    expect(await screen.findByText('❌ Файл слишком большой. Максимальный размер — 50 МБ.')).toBeInTheDocument();
  });

  it('без преподавателей отправка недоступна', async () => {
    const { user } = start({ ...studentRoutes, 'GET /teachers': [] });
    expect(await screen.findByText('Нет доступных преподавателей. Попробуйте позже.')).toBeInTheDocument();
    await user.upload(document.getElementById('file-upload'), new File(['%PDF'], 'a.pdf', { type: 'application/pdf' }));
    expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled();
  });

  it('выбранный файл не теряется при переходе между вкладками', async () => {
    const { user } = start(studentRoutes);
    await screen.findByRole('heading', { name: 'Загрузка работы' });
    await user.upload(document.getElementById('file-upload'), new File(['%PDF'], 'черновик.pdf', { type: 'application/pdf' }));
    await user.click(tabButton('🔔'));
    await user.click(tabButton('📁'));
    expect(screen.getByText('черновик.pdf')).toBeInTheDocument();
  });

  it('показывает версии одной работы одной карточкой', async () => {
    const { user } = start(studentRoutes);
    await screen.findByRole('heading', { name: 'Загрузка работы' });
    await user.click(tabButton('🔔'));

    const cards = await waitFor(() => {
      const found = document.querySelectorAll('.notif-line');
      expect(found).toHaveLength(2);
      return found;
    });
    expect(cards[0]).toHaveTextContent('Kursach.pdf');
    expect(cards[0]).toHaveTextContent('v1 🔄');
    expect(cards[0]).toHaveTextContent('v2 ✅');
    expect(cards[0]).toHaveTextContent('✅ Оформление ОК');
    expect(cards[1]).toHaveTextContent('❌ Ошибки оформления');
    expect(cards[1]).toHaveClass('notif-error');
  });

  it('отзывает работу только после подтверждения', async () => {
    const { api, user } = start({ ...studentRoutes, 'DELETE /submissions/5': {} });
    await screen.findByRole('heading', { name: 'Загрузка работы' });
    await user.click(tabButton('🔔'));
    await user.click(await screen.findByRole('button', { name: 'Отозвать работу' }));

    expect(screen.getByText('Отозвать работу?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(screen.queryByText('Отозвать работу?')).not.toBeInTheDocument();
    expect(api.find('DELETE', '/submissions/5')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Отозвать работу' }));
    await user.click(screen.getByRole('button', { name: 'Отозвать' }));
    await waitFor(() => expect(api.find('DELETE', '/submissions/5')).toHaveLength(1));
  });
});

describe('кабинет преподавателя', () => {
  it('показывает очередь и число работ на значке', async () => {
    start(teacherRoutes);
    expect(await screen.findByText('Иванов И.И. (гр. 321702)')).toBeInTheDocument();
    expect(screen.getByText('Кузнецова А.А. (гр. 321701)')).toBeInTheDocument();
    expect(document.querySelector('.nav-badge')).toHaveTextContent('2');
  });

  it('поиск находит студента с опечаткой и сообщает, если никого нет', async () => {
    const { user } = start(teacherRoutes);
    await screen.findByText('Иванов И.И. (гр. 321702)');
    const search = screen.getByPlaceholderText('Поиск по группе, студенту или файлу...');

    await user.type(search, 'кузнецва');
    expect(screen.queryByText('Иванов И.И. (гр. 321702)')).not.toBeInTheDocument();
    expect(screen.getByText('Кузнецова А.А. (гр. 321701)')).toBeInTheDocument();

    await user.clear(search);
    await user.type(search, '999999');
    expect(screen.getByText('По этому запросу работ не найдено')).toBeInTheDocument();
  });

  it('принимает работу', async () => {
    const { api, user } = start({ ...teacherRoutes, 'POST /teacher/submissions/1/verdict': {} });
    await user.click(await screen.findByText('kursach.pdf'));
    await user.click(screen.getByRole('button', { name: '✅ Принять' }));
    await waitFor(() => expect(api.find('POST', '/teacher/submissions/1/verdict')).toHaveLength(1));
    expect(api.find('POST', '/teacher/submissions/1/verdict')[0].body).toEqual({ verdict: 'APPROVED', comment: '' });
  });

  it('отправляет на доработку с комментарием и предлагает его как шаблон в следующий раз', async () => {
    const { api, user } = start({ ...teacherRoutes, 'POST /teacher/submissions/1/verdict': {} });
    await user.click(await screen.findByText('kursach.pdf'));
    await user.click(screen.getByRole('button', { name: '🔄 На доработку' }));

    const send = screen.getByRole('button', { name: 'Отправить' });
    expect(send).toBeDisabled();
    await user.type(screen.getByPlaceholderText('Комментарий для студента...'), 'Исправить нумерацию страниц');
    expect(screen.getByText('27 символов')).toBeInTheDocument();
    await user.click(send);

    await waitFor(() => expect(api.find('POST', '/teacher/submissions/1/verdict')).toHaveLength(1));
    expect(api.find('POST', '/teacher/submissions/1/verdict')[0].body).toEqual({
      verdict: 'REVISION', comment: 'Исправить нумерацию страниц',
    });
    // после вердикта карточка сворачивается
    await waitFor(() => expect(screen.queryByRole('button', { name: '✅ Принять' })).not.toBeInTheDocument());

    await user.click(screen.getByText('zapiska.pdf'));
    await user.click(screen.getByRole('button', { name: '🔄 На доработку' }));
    await user.click(screen.getByRole('button', { name: 'Исправить нумерацию страниц' }));
    expect(screen.getByPlaceholderText('Комментарий для студента...')).toHaveValue('Исправить нумерацию страниц');
  });

  it('в истории сворачивает версии одной работы и раскрывает их по нажатию', async () => {
    const { user } = start(teacherRoutes);
    await screen.findByText('Иванов И.И. (гр. 321702)');
    await user.click(tabButton('📚'));

    expect(await screen.findByText('✅ Принято')).toBeInTheDocument();
    expect(screen.getByText('2 версии')).toBeInTheDocument();
    expect(screen.queryByText('💬 Нет списка литературы')).not.toBeInTheDocument();

    await user.click(screen.getByText('old.pdf'));
    expect(screen.getByText('💬 Нет списка литературы')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '📄 Скачать v1' })).toBeInTheDocument();
  });

  it('заметка сохраняется, а 🔍 открывает очередь с поиском по студенту из неё', async () => {
    const { user } = start(teacherRoutes);
    await screen.findByText('Иванов И.И. (гр. 321702)');
    await user.click(tabButton('📝'));
    expect(screen.getByText('Нет заметок. Нажмите «+ Новая заметка»')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '+ Новая заметка' }));
    await user.type(screen.getByPlaceholderText('Введите заметку...'), 'Спросить Кузнецова из 321701 про титульный лист');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(JSON.parse(localStorage.getItem('sapsr_notes_42'))[0].text).toBe('Спросить Кузнецова из 321701 про титульный лист');

    await user.click(screen.getByTitle('Найти студента'));
    expect(screen.getByPlaceholderText('Поиск по группе, студенту или файлу...')).toHaveValue('Кузнецова 321701');
    expect(screen.queryByText('Иванов И.И. (гр. 321702)')).not.toBeInTheDocument();
    expect(screen.getByText('Кузнецова А.А. (гр. 321701)')).toBeInTheDocument();
  });

  it('заметка удаляется после подтверждения', async () => {
    localStorage.setItem('sapsr_notes_42', JSON.stringify([{ id: 1, text: 'Старая заметка' }]));
    const { user } = start(teacherRoutes);
    await screen.findByText('Иванов И.И. (гр. 321702)');
    await user.click(tabButton('📝'));
    await user.click(screen.getByTitle('Удалить'));
    expect(screen.getByText('Удалить заметку?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Удалить' }));
    expect(screen.queryByText('Старая заметка')).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('sapsr_notes_42'))).toEqual([]);
  });
});

describe('настройки и обучение', () => {
  it('тема, контраст и размер шрифта применяются и запоминаются', async () => {
    const { user } = start(studentRoutes);
    await screen.findByRole('heading', { name: 'Загрузка работы' });
    await user.click(tabButton('⚙️'));
    const app = document.querySelector('.App');
    expect(app).toHaveClass('font-normal');
    expect(app).not.toHaveClass('app-dark');

    await user.click(screen.getByRole('button', { name: 'Переключить ночной режим' }));
    await user.click(screen.getByRole('button', { name: 'Переключить контрастный режим' }));
    expect(app).toHaveClass('app-dark', 'app-contrast');
    expect(localStorage.getItem('sapsr_theme')).toBe('dark');
    expect(localStorage.getItem('sapsr_contrast')).toBe('1');
  });

  it('сохранённые настройки применяются при запуске', async () => {
    localStorage.setItem('sapsr_theme', 'dark');
    localStorage.setItem('sapsr_fontsize', 'xlarge');
    start(studentRoutes);
    await screen.findByRole('heading', { name: 'Загрузка работы' });
    expect(document.querySelector('.App')).toHaveClass('app-dark', 'font-xlarge');
  });

  it('после выхода можно войти под сохранённой ролью', async () => {
    const { user } = start(teacherRoutes);
    await screen.findByText('Иванов И.И. (гр. 321702)');
    await user.click(tabButton('⚙️'));
    await user.click(screen.getByRole('button', { name: 'Выйти из системы' }));
    expect(screen.getByText('Выберите вашу роль в системе')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Войти' }));
    expect(await screen.findByRole('heading', { name: 'Работы студентов' })).toBeInTheDocument();
  });

  it('обучение ведёт по вкладкам и запоминает, что пройдено', async () => {
    const { user } = start(teacherRoutes);
    await screen.findByText('Иванов И.И. (гр. 321702)');
    await user.click(screen.getByTitle('Справка'));
    expect(screen.getByText('1 / 8')).toBeInTheDocument();

    const next = () => user.click(document.querySelector('.tutorial-overlay'));
    await next(); await next(); await next();
    expect(screen.getByText('4 / 8')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'История проверок' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Пропустить' }));
    expect(document.querySelector('.tutorial-overlay')).not.toBeInTheDocument();
    expect(localStorage.getItem('sapsr_onboarded_teacher')).toBe('1');
  });
});
