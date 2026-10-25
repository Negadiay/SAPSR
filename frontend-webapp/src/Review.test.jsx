import { describe, it, expect } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import { mockApi, jsonResponse, stubTelegram } from './test/mockApi';

// Кабинет преподавателя: очередь, работа, файл с пометками и решение (api-v1.md, разделы 6, 8, 9).

const FINDINGS = JSON.stringify([
  { severity: 'minor', category: 'format', origin: 'symbolic', message: 'Межстрочный интервал меньше полуторного.' },
  { severity: 'major', category: 'content', origin: 'neural', needs_teacher_review: true, page: 12,
    message: 'Не удалось установить, выполнено ли требование «Обработка рассогласований».' },
]);

const PENDING = [
  { id: 21, student_name: 'Терешкова А.В.', student_group: '321702', file_name: 'КП7_321702_Терешкова.pdf',
    status: 'SUCCESS', content_status: 'DONE', format_errors: FINDINGS, version: 2,
    created_at: '2026-10-25T09:15:00', first_submitted_at: '2026-10-18T20:00:00', review_annotations: 0, has_review_file: false },
  { id: 22, student_name: 'Иванов И.И.', student_group: '321701', file_name: 'курсовая.pdf',
    status: 'REJECTED', content_status: 'PENDING', format_errors: '[{"severity":"major","category":"format","origin":"symbolic","message":"Левое поле"}]',
    version: 1, created_at: '2026-10-24T22:47:00', review_annotations: 0, has_review_file: false },
];

const HISTORY = [
  { id: 11, student_name: 'Сидоров П.П.', student_group: '321702', file_name: 'Сидоров.pdf', teacher_verdict: 'REVISION',
    version: 1, created_at: '2026-10-10T11:00:00', has_review_file: true, review_annotations: 1 },
];

const teacherRoutes = {
  'GET /me': { role: 'TEACHER', telegram_id: 7, full_name: 'Гракова Наталья Викторовна' },
  'GET /teacher/submissions': PENDING,
  'GET /teacher/history': HISTORY,
  'GET /submissions/11/annotations': { annotations: [{ id: 5, page: 2, kind: 'NOTE', comment: 'Добавьте схему' }] },
};

const start = (routes = teacherRoutes) => {
  stubTelegram({ userId: 7 });
  const api = mockApi(routes);
  const user = userEvent.setup();
  render(<App />);
  return { api, user };
};

const heading = (name) => screen.findByRole('heading', { name });
const openWork = async (user, name) => user.click(await screen.findByRole('button', { name: new RegExp(name) }));

describe('очередь', () => {
  it('работы на проверке с итогом автоматической проверки; проверенные — отдельно', async () => {
    const { user } = start();
    expect(await heading('Работы')).toBeInTheDocument();
    expect(await screen.findByText('Терешкова А.В.')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /На проверке/ })).toHaveTextContent('2');
    expect(screen.getByText('Оформление без ошибок')).toBeInTheDocument();
    expect(screen.getByText('Содержание: 1 замечание, на ваше решение: 1')).toBeInTheDocument();
    expect(screen.getByText('Ошибок оформления: 1')).toBeInTheDocument();
    expect(screen.getByText('Содержание проверяется')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Проверено' }));
    expect(screen.getByText('Сидоров П.П.')).toBeInTheDocument();
    expect(screen.getByText('На доработке')).toBeInTheDocument();
    expect(screen.queryByText('Терешкова А.В.')).toBeNull();
  });

  it('поиск по фамилии с опечаткой и фильтр по группе', async () => {
    const { user } = start();
    await screen.findByText('Терешкова А.В.');
    await user.type(screen.getByRole('searchbox', { name: 'Поиск' }), 'терешкво');
    expect(screen.getByText('Терешкова А.В.')).toBeInTheDocument();
    expect(screen.queryByText('Иванов И.И.')).toBeNull();
    await user.clear(screen.getByRole('searchbox', { name: 'Поиск' }));

    await user.click(screen.getByRole('button', { name: '321701' }));
    expect(screen.getByText('Иванов И.И.')).toBeInTheDocument();
    expect(screen.queryByText('Терешкова А.В.')).toBeNull();
  });

  it('без работ — что будет дальше', async () => {
    start({ ...teacherRoutes, 'GET /teacher/submissions': [] });
    expect(await screen.findByText('Новых работ нет.')).toBeInTheDocument();
    expect(screen.getByText('О новой работе сообщим в чат.')).toBeInTheDocument();
  });
});

describe('работа и решение', () => {
  it('на доработку — только с файлом с пометками, после подтверждения', async () => {
    let pending = PENDING;
    const { api, user } = start({
      ...teacherRoutes,
      'GET /teacher/submissions': () => pending,
      'POST /teacher/submissions/21/review-file': {
        review_file_id: 31, warnings: [],
        annotations: [{ id: 1, page: 3, kind: 'HIGHLIGHT', quoted_text: 'Система', comment: 'Уточните' }],
      },
      'POST /teacher/submissions/21/verdict': () => { pending = PENDING.slice(1); return { status: 'OK' }; },
    });
    await openWork(user, 'Терешкова');
    expect(await heading('Работа')).toBeInTheDocument();
    expect(screen.getByText('Получена 25.10.2026, 09:15')).toBeInTheDocument();
    expect(screen.getByText('Версия 2, первая — 18.10, 20:00')).toBeInTheDocument();
    expect(screen.getByText(/найдено нейросетью · на ваше решение/)).toBeInTheDocument();

    const revise = screen.getByRole('button', { name: 'На доработку' });
    expect(revise).toBeDisabled();
    expect(screen.getByText('Для доработки загрузите файл с пометками.')).toBeInTheDocument();

    await user.upload(screen.getByLabelText('Загрузить файл'), new File(['%PDF'], 'review.pdf', { type: 'application/pdf' }));
    expect(await screen.findByText('Загружено: 1 комментарий')).toBeInTheDocument();
    expect(screen.getByText('Уточните')).toBeInTheDocument();
    expect(revise).toBeEnabled();

    await user.click(revise);
    expect(screen.getByRole('dialog', { name: 'Вернуть на доработку?' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Вернуть' }));
    await waitFor(() => expect(api.find('POST', '/teacher/submissions/21/verdict')).toHaveLength(1));
    expect(api.find('POST', '/teacher/submissions/21/verdict')[0].body).toEqual({ verdict: 'REVISION', review_file_id: 31 });
    expect(await heading('Работы')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Терешкова А.В.')).toBeNull());
  });

  it('«Принять» — с подтверждением: отмена ничего не отправляет', async () => {
    const { api, user } = start({ ...teacherRoutes, 'POST /teacher/submissions/22/verdict': { status: 'OK' } });
    await openWork(user, 'Иванов');
    await user.click(await screen.findByRole('button', { name: 'Принять' }));
    const dialog = screen.getByRole('dialog', { name: 'Принять работу?' });
    expect(within(dialog).getByText('Студент получит уведомление. Изменить решение будет нельзя.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Отмена' }));
    expect(api.find('POST', '/teacher/submissions/22/verdict')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Принять' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Принять' }));
    await waitFor(() => expect(api.find('POST', '/teacher/submissions/22/verdict')).toHaveLength(1));
    expect(api.find('POST', '/teacher/submissions/22/verdict')[0].body).toEqual({ verdict: 'APPROVED' });
  });

  it('ошибка решения видна на экране, без системного окна', async () => {
    const { user } = start({
      ...teacherRoutes,
      'POST /teacher/submissions/22/verdict': jsonResponse({ code: 'VERDICT_ALREADY_SET', error: 'Решение по работе уже принято.' }, 409),
    });
    await openWork(user, 'Иванов');
    await user.click(await screen.findByRole('button', { name: 'Принять' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Принять' }));
    expect(await screen.findByText('Решение по работе уже принято.')).toBeInTheDocument();
  });

  it('чужой или пустой файл — понятное предупреждение', async () => {
    const { user } = start({
      ...teacherRoutes,
      'POST /teacher/submissions/22/review-file': { review_file_id: 32, annotations: [], warnings: [{ code: 'NO_ANNOTATIONS' }, { code: 'DIFFERENT_DOCUMENT' }] },
    });
    await openWork(user, 'Иванов');
    await user.upload(await screen.findByLabelText('Загрузить файл'), new File(['%PDF'], 'r.pdf', { type: 'application/pdf' }));
    expect(await screen.findByText('Комментариев нет — сохраните файл не через «Печать в PDF».')).toBeInTheDocument();
    expect(screen.getByText('Похоже, это файл другой работы.')).toBeInTheDocument();
    expect(screen.getByText('Загружено: 0 комментариев')).toBeInTheDocument();
  });

  it('проверенная работа — решение и комментарии, без кнопок решения', async () => {
    const { user } = start();
    await screen.findByText('Терешкова А.В.');
    await user.click(screen.getByRole('tab', { name: 'Проверено' }));
    await openWork(user, 'Сидоров');
    expect(await screen.findByText('Вы вернули работу на доработку')).toBeInTheDocument();
    expect(await screen.findByText('Добавьте схему')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Принять' })).toBeNull();
  });
});

describe('заметки', () => {
  it('заметка ищет работы упомянутого студента', async () => {
    const { user } = start();
    await screen.findByText('Терешкова А.В.');
    await user.click(screen.getByTitle('Заметки'));
    expect(await heading('Заметки')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Новая заметка' }));
    await user.type(screen.getByLabelText('Текст заметки'), 'Иванов 321701 — проверить поля');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(screen.getByText('Иванов 321701 — проверить поля')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Найти работы студента' }));
    expect(await heading('Работы')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Поиск' })).toHaveValue('Иванов 321701');
    expect(screen.getByText('Иванов И.И.')).toBeInTheDocument();
    expect(screen.queryByText('Терешкова А.В.')).toBeNull();
  });

  it('удаление — после подтверждения', async () => {
    localStorage.setItem('sapsr_notes_7', JSON.stringify([{ id: 1, text: 'Старая заметка' }]));
    const { user } = start();
    await screen.findByText('Терешкова А.В.');
    await user.click(screen.getByTitle('Заметки'));
    await user.click(await screen.findByRole('button', { name: 'Удалить заметку' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Удалить' }));
    expect(screen.queryByText('Старая заметка')).toBeNull();
    expect(screen.getByText('Заметок нет.')).toBeInTheDocument();
  });
});
