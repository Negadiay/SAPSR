import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import { mockApi, jsonResponse, stubTelegram } from './test/mockApi';
import { shortName } from './lib/names';
import { summarizeFindings } from './lib/findings';
import { workFileName } from './lib/workName';

// Сквозной путь работы: профиль при загрузке → итог автопроверки у преподавателя →
// PDF с пометками → вердикт → комментарии у студента. Плюс «кто вошёл» в настройках.

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }) => children,
  motion: { div: ({ className, children }) => <div className={className}>{children}</div> },
}));

const FINDINGS = JSON.stringify([
  { severity: 'major', category: 'format', origin: 'symbolic', page: 3, message: 'Левое поле меньше 30 мм' },
  { severity: 'warning', category: 'content', origin: 'hybrid', page: 4, needs_teacher_review: false,
    message: 'Форматы входных файлов описаны по-разному' },
  { severity: 'warning', category: 'requirements', origin: 'hybrid', needs_teacher_review: true,
    message: 'Не удалось установить, описаны ли точки интеграции' },
]);

const QUEUE = [{
  id: 153, student_name: 'Терешкова А.В. (гр. 321702)', file_name: 'Курсовая.pdf', status: 'REJECTED',
  created_at: '2026-10-21T12:00:00', first_submitted_at: '2026-10-20T09:15:00', version: 2,
  format_errors: FINDINGS, review_annotations: 0, has_review_file: false,
}];

const ANNOTATIONS = [
  { id: 31, page: 4, kind: 'HIGHLIGHT', quoted_text: 'PDF и DOCX', comment: 'Сверьте с разделом 1.5', reply_to_id: null },
  { id: 32, page: 4, kind: 'NOTE', comment: 'И с заключением', reply_to_id: 31 },
  { id: 33, page: 9, kind: 'INK', reply_to_id: null },
];

const teacherRoutes = {
  'GET /me': { role: 'TEACHER', telegram_id: 42, full_name: 'Гракова Наталья Викторовна',
    last_name: 'Гракова', first_name: 'Наталья', middle_name: 'Викторовна' },
  'GET /teacher/submissions': QUEUE,
  'GET /teacher/history': [],
};

// Курс, семестр и этап определяет сервер (решение 11): 321702 осенью 2026 года — 4 курс, 7 семестр.
const studentRoutes = {
  'GET /me': { role: 'STUDENT', telegram_id: 42, full_name: 'Терешкова А.В. (гр. 321702)',
    last_name: 'Терешкова', first_name: null, middle_name: null, group: '321702',
    course: 4, semester: 7, stage: 2, course_overridden: false },
  'GET /teachers': [{ telegram_id: 7, full_name: 'Гракова Н.В.' }],
  'GET /submissions': [],
};

const start = (routes) => {
  stubTelegram();
  const api = mockApi(routes);
  const user = userEvent.setup();
  render(<App />);
  return { api, user };
};

const tabButton = (icon) => within(document.querySelector('.bottom-nav')).getByRole('button', { name: new RegExp(icon) });

describe('загрузка с профилем проверки', () => {
  it('студент выбирает только вид работы, курс, семестр и этап показаны', async () => {
    const { api, user } = start({ ...studentRoutes, 'POST /upload': {} });
    await screen.findByRole('option', { name: 'Гракова Н.В.' });
    expect(screen.getByText('4 курс · 7 семестр')).toBeInTheDocument();
    expect(screen.getByText('проверка к 2-й опроцентовке')).toBeInTheDocument();
    expect(screen.queryByLabelText('Семестр')).toBeNull();
    expect(screen.queryByLabelText('Этап')).toBeNull();

    await user.selectOptions(screen.getByLabelText('Вид работы'), 'course_work');
    await user.upload(document.getElementById('file-upload'), new File(['%PDF'], 'Курсовая.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Отправить' }));

    await waitFor(() => expect(api.find('POST', '/upload')).toHaveLength(1));
    const { body } = api.find('POST', '/upload')[0];
    expect(body.get('work_type')).toBe('course_work');
    expect(body.has('semester')).toBe(false);
    expect(body.has('stage')).toBe(false);
    expect(JSON.parse(localStorage.getItem('sapsr_last_profile'))).toEqual({ work_type: 'course_work' });
  });

  it('прежний выбор семестра из памяти не берётся', async () => {
    localStorage.setItem('sapsr_last_profile', JSON.stringify({ work_type: 'course_project', semester: '1', stage: '3' }));
    start(studentRoutes);
    await screen.findByRole('option', { name: 'Гракова Н.В.' });
    expect(screen.getByLabelText('Вид работы')).toHaveValue('course_project');
    expect(screen.getByText('Загрузите документ с названием КП7_321702_Терешкова.pdf')).toBeInTheDocument();
  });

  it('если семестр не определился, говорит, к кому обратиться', async () => {
    start({ ...studentRoutes, 'GET /me': { ...studentRoutes['GET /me'], group: '921701', course: null, semester: null } });
    expect(await screen.findByText(/Семестр по группе 921701 не определился/)).toHaveTextContent('сообщите администратору');
  });

  it('после всех опроцентовок — итоговая сдача', async () => {
    start({ ...studentRoutes, 'GET /me': { ...studentRoutes['GET /me'], stage: null } });
    expect(await screen.findByText('итоговая сдача')).toBeInTheDocument();
  });

  it('без профиля поля не передаются вовсе', async () => {
    const { api, user } = start({ ...studentRoutes, 'POST /upload': {} });
    await screen.findByRole('option', { name: 'Гракова Н.В.' });
    await user.upload(document.getElementById('file-upload'), new File(['%PDF'], 'a.pdf', { type: 'application/pdf' }));
    await user.click(screen.getByRole('button', { name: 'Отправить' }));

    await waitFor(() => expect(api.find('POST', '/upload')).toHaveLength(1));
    const { body } = api.find('POST', '/upload')[0];
    expect(body.has('semester')).toBe(false);
    expect(body.has('work_type')).toBe(false);
  });
});

describe('работа у преподавателя', () => {
  it('отклонённая работа видна до конца проверки содержания', async () => {
    start({ ...teacherRoutes, 'GET /teacher/submissions': [{ ...QUEUE[0], content_status: 'PENDING' }] });
    const card = (await screen.findByText('Курсовая.pdf')).closest('.ts-card-compact');
    expect(card).toHaveTextContent('🧠 Содержание ещё проверяется');
    expect(card).not.toHaveTextContent('🧠 Содержание: 2');
  });

  it('отклонённая работа видна с итогом проверки, версией и датой первой версии', async () => {
    const { user } = start(teacherRoutes);
    const card = (await screen.findByText('Курсовая.pdf')).closest('.ts-card-compact');

    expect(card).toHaveTextContent('❌ Ошибки оформления: 1');
    expect(card).toHaveTextContent('🧠 Содержание: 2 (на вашу проверку: 1)');
    expect(card).toHaveTextContent(/Версия 2 · первая пришла 20\.10\.2026/);

    await user.click(screen.getByText('Курсовая.pdf'));
    expect(card).toHaveTextContent('Форматы входных файлов описаны по-разному');
    expect(card).toHaveTextContent('стр. 4 · правило + модель');
    expect(card).toHaveTextContent('требует вашей проверки');
    expect(within(card).getByRole('button', { name: '📋 Отчёт проверки' })).toBeInTheDocument();
  });

  it('после загрузки работы с пометками — коротко «загружен», и можно на доработку', async () => {
    const { api, user } = start({
      ...teacherRoutes,
      'POST /teacher/submissions/153/review-file': {
        review_file_id: 7, annotations: ANNOTATIONS,
        warnings: [{ code: 'DIFFERENT_DOCUMENT', message: 'длинный текст сервера' }],
      },
      'POST /teacher/submissions/153/verdict': {},
    });
    await user.click(await screen.findByText('Курсовая.pdf'));
    expect(screen.getByRole('button', { name: '🔄 На доработку' })).toBeDisabled();
    await user.upload(document.getElementById('review-file-153'),
      new File(['%PDF'], 'Курсовая с пометками.pdf', { type: 'application/pdf' }));

    expect(await screen.findByText('✅ Документ успешно загружен')).toBeInTheDocument();
    expect(screen.getByText('⚠️ Похоже, это другой документ, а не работа этого студента.')).toBeInTheDocument();
    // Что именно прочиталось, преподавателю не показываем — это увидит студент.
    expect(screen.queryByText('Сверьте с разделом 1.5')).toBeNull();
    expect(screen.queryByText(/Прочитано комментариев/)).toBeNull();
    expect(api.find('POST', '/teacher/submissions/153/review-file')[0].body.get('file').name)
      .toBe('Курсовая с пометками.pdf');

    await user.click(screen.getByRole('button', { name: '🔄 На доработку' }));
    await waitFor(() => expect(api.find('POST', '/teacher/submissions/153/verdict')).toHaveLength(1));
    expect(api.find('POST', '/teacher/submissions/153/verdict')[0].body)
      .toEqual({ verdict: 'REVISION', comment: '', review_file_id: 7 });
  });

  it('документ без комментариев — понятное предупреждение, на доработку не отправить', async () => {
    const { user } = start({
      ...teacherRoutes,
      'POST /teacher/submissions/153/review-file': {
        review_file_id: 7, annotations: [], warnings: [{ code: 'NO_ANNOTATIONS', message: '...' }],
      },
    });
    await user.click(await screen.findByText('Курсовая.pdf'));
    await user.upload(document.getElementById('review-file-153'), new File(['%PDF'], 'print.pdf', { type: 'application/pdf' }));

    expect(await screen.findByText(/В документе нет комментариев/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '🔄 На доработку' })).toBeDisabled();
  });

  it('не PDF — понятная ошибка, на доработку не отправить', async () => {
    const { user } = start({
      ...teacherRoutes,
      'POST /teacher/submissions/153/review-file': jsonResponse(
        { code: 'NOT_A_PDF', error: 'Файл не открывается как PDF.' }, 400),
    });
    await user.click(await screen.findByText('Курсовая.pdf'));
    await user.upload(document.getElementById('review-file-153'), new File(['x'], 'scan.pdf', { type: 'application/pdf' }));

    expect(await screen.findByText('❌ Файл не открывается как PDF.')).toBeInTheDocument();
    expect(screen.queryByText('✅ Документ успешно загружен')).toBeNull();
    expect(screen.getByRole('button', { name: '🔄 На доработку' })).toBeDisabled();
  });
});

describe('имя файла по форме', () => {
  it('подсказывает «КР7_321702_Терешкова.pdf» по виду работы и семестру с сервера', async () => {
    const { user } = start(studentRoutes);
    await screen.findByRole('option', { name: 'Гракова Н.В.' });
    expect(screen.getByText('Нажмите, чтобы выбрать файл (.pdf)')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Вид работы'), 'course_work');
    expect(screen.getByText('Загрузите документ с названием КР7_321702_Терешкова.pdf')).toBeInTheDocument();

    await user.upload(document.getElementById('file-upload'), new File(['%PDF'], 'курсач финал.pdf', { type: 'application/pdf' }));
    expect(screen.getByText('Название по форме: КР7_321702_Терешкова.pdf')).toBeInTheDocument();
  });

  it('файл с правильным названием не вызывает подсказку', async () => {
    const { user } = start(studentRoutes);
    await screen.findByRole('option', { name: 'Гракова Н.В.' });
    await user.selectOptions(screen.getByLabelText('Вид работы'), 'course_project');
    await user.upload(document.getElementById('file-upload'), new File(['%PDF'], 'кп7_321702_терешкова.pdf', { type: 'application/pdf' }));
    expect(screen.queryByText(/Название по форме/)).toBeNull();
  });
});

describe('комментарии у студента', () => {
  it('после вердикта показывает комментарии из файла по страницам', async () => {
    const { api, user } = start({
      ...studentRoutes,
      'GET /submissions': [{
        id: 153, file_name: 'Курсовая.pdf', status: 'REJECTED', teacher_verdict: 'REVISION',
        created_at: '2026-10-21T12:00:00', has_review_file: true, review_annotations: 3,
      }],
      'GET /submissions/153/annotations': { review_file_id: 7, annotations: ANNOTATIONS },
    });
    await screen.findByRole('heading', { name: 'Загрузка работы' });
    await user.click(tabButton('🔔'));

    await user.click(await screen.findByRole('button', { name: /Комментарии в файле: 3/ }));
    expect(await screen.findByText('Сверьте с разделом 1.5')).toBeInTheDocument();
    expect(screen.getByText('Страница 9')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Скачать файл с пометками' })).toBeInTheDocument();
    expect(api.find('GET', '/submissions/153/annotations')).toHaveLength(1);
  });
});

describe('кто вошёл', () => {
  it('студент видит фамилию с инициалами и группу', async () => {
    const { user } = start(studentRoutes);
    await screen.findByRole('heading', { name: 'Загрузка работы' });
    await user.click(tabButton('⚙️'));

    expect(screen.getByText('Терешкова А. В.')).toBeInTheDocument();
    expect(screen.getByText('Группа 321702 · 4 курс')).toBeInTheDocument();
  });

  it('преподаватель видит фамилию с инициалами без группы', async () => {
    const { user } = start(teacherRoutes);
    await screen.findByText('Курсовая.pdf');
    await user.click(tabButton('⚙️'));

    expect(screen.getByText('Гракова Н. В.')).toBeInTheDocument();
    expect(screen.queryByText(/^Группа/)).not.toBeInTheDocument();
  });
});

describe('вспомогательные функции', () => {
  it('shortName собирает «Фамилия И. О.» из полей и из старой формы', () => {
    expect(shortName({ last_name: 'Гракова', first_name: 'Наталья', middle_name: 'Викторовна' })).toBe('Гракова Н. В.');
    expect(shortName({ last_name: 'Терешкова', full_name: 'Терешкова А.В. (гр. 321702)' })).toBe('Терешкова А. В.');
    expect(shortName({ full_name: 'Иванов И.И. (гр. 321702)' })).toBe('Иванов И. И.');
    expect(shortName({ last_name: 'Smith', first_name: 'John' })).toBe('Smith J.');
    expect(shortName({ full_name: 'Петрова Анна Сергеевна' })).toBe('Петрова А. С.');
    expect(shortName({})).toBe('');
    expect(shortName(null)).toBe('');
  });

  it('summarizeFindings отделяет содержание от оформления и терпит мусор', () => {
    const summary = summarizeFindings(FINDINGS);
    expect(summary.formatErrors).toBe(1);
    expect(summary.formatWarnings).toBe(0);
    expect(summary.content).toHaveLength(2);
    expect(summary.needsReview).toBe(1);
    expect(summarizeFindings('не JSON').content).toEqual([]);
    expect(summarizeFindings(null).formatErrors).toBe(0);
  });

  it('workFileName собирает имя по форме и подсказывает пропуски', () => {
    expect(workFileName({ workType: 'course_work', semester: '7', group: '321702', lastName: 'Терешкова' }))
      .toBe('КР7_321702_Терешкова.pdf');
    expect(workFileName({ workType: 'diploma', semester: 8, group: '321702', lastName: 'Терешкова' }))
      .toBe('ДР8_321702_Терешкова.pdf');
    expect(workFileName({ workType: 'course_project' })).toBe('КП<семестр>_<группа>_<фамилия>.pdf');
    expect(workFileName({ workType: '' })).toBe('');
  });
});
