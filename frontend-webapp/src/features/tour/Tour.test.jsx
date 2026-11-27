import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../../App';
import { resetLaunchScreen } from '../../navigation/useNavigator';
import { mockApi, stubTelegram } from '../../test/mockApi';
import { tourSteps } from './steps';

// Интерактивное обучение: шаги идут по экранам роли, и у каждого шага есть что подсветить.

const STUDENT = {
  'GET /me': { role: 'STUDENT', telegram_id: 42, full_name: 'Иванов И.И. (гр. 321702)', group: '321702',
    course: 4, semester: 7, stage: 2, deadline: '2026-12-26' },
  'GET /teachers': [{ telegram_id: 7, full_name: 'Петров Пётр Петрович' }],
  'GET /submissions': [],
};

const TEACHER = {
  'GET /me': { role: 'TEACHER', telegram_id: 7, full_name: 'Гракова Наталья Викторовна' },
  'GET /teacher/submissions': [], 'GET /teacher/history': [],
};

const ADMIN = {
  'GET /me': { role: 'ADMIN', telegram_id: 9001, full_name: '' },
  'GET /admin/calendar': {
    today: '2026-10-26', academic_year: 2026, academic_year_title: '2026/2027', half: 'AUTUMN',
    half_by_date: 'AUTUMN', half_overridden: false, max_course: 4, group_digits: [3, 4, 5, 6],
    date_window: { from: '2026-08-01', to: '2027-01-31' },
    schedule: [{ course: 4, semester: 7, dates: { 1: null, 2: null, 3: null, deadline: null }, current_stage: null }],
    groups: [],
  },
  'GET /admin/system': {
    worker: { known: true, online: true, seconds_ago: 5, mode: 'hybrid', llm_mode: 'live', cloud_allowed: false },
    models: [{ id: 'main', title: 'Основная', tier: 'main', model: 'qwen3.5:4b', cloud: false, available: true }],
    default_model: 'main', selected: null, active: 'main',
  },
};

const start = (routes, telegram) => {
  stubTelegram(telegram);
  mockApi(routes);
  const user = userEvent.setup();
  render(<App />);
  return user;
};

const card = () => screen.getByRole('dialog');
const target = (id) => document.querySelector(`[data-tour="${id}"]`);

// Проходит обучение до конца и на каждом шаге проверяет заголовок и подсвечиваемый элемент.
const walk = async (user, role) => {
  const steps = tourSteps(role);
  for (const [i, step] of steps.entries()) {
    await waitFor(() => expect(within(card()).getByText(step.title)).toBeInTheDocument());
    expect(within(card()).getByText(`Шаг ${i + 1} из ${steps.length}`)).toBeInTheDocument();
    if (step.target) expect(target(step.target), `${role}: ${step.target}`).not.toBeNull();
    await user.click(within(card()).getByRole('button', { name: i + 1 === steps.length ? 'Готово' : 'Далее' }));
  }
};

afterEach(() => resetLaunchScreen());

describe('обучение', () => {
  it('студент: проходит экраны «Мои работы» и «Сдать работу» и возвращается на главный', async () => {
    const user = start(STUDENT);
    await user.click(await screen.findByTitle('Обучение'));
    await walk(user, 'student');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('heading', { name: 'Мои работы' })).toBeInTheDocument();
    expect(localStorage.getItem('sapsr_onboarded_student')).toBe('1');
  });

  it('преподаватель: работы, поиск, заметки', async () => {
    const user = start(TEACHER);
    await user.click(await screen.findByTitle('Обучение'));
    await walk(user, 'teacher');
    expect(screen.getByRole('heading', { name: 'Работы' })).toBeInTheDocument();
  });

  it('администратор: обучение начинается само и показывает красную зону', async () => {
    const user = start(ADMIN);
    expect(await screen.findByRole('dialog', { name: 'Управление' }, { timeout: 3000 })).toBeInTheDocument();
    await walk(user, 'admin');
    expect(localStorage.getItem('sapsr_onboarded_admin')).toBe('1');
  });

  it('в Telegram кнопку «Сдать работу» не подсветить — сказано, где она', async () => {
    const user = start(STUDENT, { native: true, platform: 'android' });
    await user.click(await screen.findByTitle('Обучение'));
    await user.click(within(card()).getByRole('button', { name: 'Далее' }));
    expect(await within(card()).findByText(/внизу экрана, под приложением/)).toBeInTheDocument();
  });

  it('назад, Esc и «Пропустить»', async () => {
    const user = start(STUDENT);
    await user.click(await screen.findByTitle('Обучение'));
    await user.click(within(card()).getByRole('button', { name: 'Далее' }));
    await user.click(within(card()).getByRole('button', { name: 'Далее' }));
    expect(await screen.findByRole('heading', { name: 'Сдать работу' })).toBeInTheDocument();
    await user.click(within(card()).getByRole('button', { name: 'Назад' }));
    await waitFor(() => expect(within(card()).getByText('Сдать работу')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: 'Мои работы' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(localStorage.getItem('sapsr_onboarded_student')).toBe('1');
  });
});
