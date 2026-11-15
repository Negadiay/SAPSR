import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { cwd } from 'node:process';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ReportView from './ReportView';
import { contentStep, formatStep } from '../lib/status';
import { confidenceLabel } from '../lib/report';
import { mockApi, jsonResponse, stubTelegram } from '../test/mockApi';

// Примеры отчёта — те же, что сверяет контракт обработчика (docs/contracts/examples/report-v2).
const example = (name) => JSON.parse(readFileSync(
  resolve(cwd(), `../docs/contracts/examples/report-v2/${name}.json`), 'utf-8'));

const WORK = { id: 5, status: 'SUCCESS', content_status: 'DONE', format_errors: '[]' };
const LEGACY = JSON.stringify([{ severity: 'major', category: 'format', origin: 'symbolic', message: 'Левое поле 15 мм вместо 30.' }]);

const show = (answer, props = {}) => {
  stubTelegram();
  const api = mockApi({ 'GET /submissions/5/report-v2': answer, ...props.routes });
  const user = userEvent.setup();
  render(<ReportView submission={{ ...WORK, ...props.work }} forTeacher={props.forTeacher} />);
  return { api, user };
};

describe('ReportView', () => {
  it('противоречие: фрагменты разных разделов рядом, происхождение поясняется по нажатию', async () => {
    const { user } = show({ report: example('contradiction'), comparison: null });
    expect(await screen.findByText(/перечни в разных разделах не совпадают/)).toBeInTheDocument();
    expect(screen.getByText('Оформление: 0 · Структура: 0 · Содержание: 1')).toBeInTheDocument();

    const quotes = document.querySelector('.evidence-compare');
    expect(within(quotes).getByText('раздел «Введение», стр. 3')).toBeInTheDocument();
    expect(within(quotes).getByText('Система принимает файлы в форматах DOCX и PDF')).toBeInTheDocument();
    expect(within(quotes).getByText('раздел «1.2», стр. 4')).toBeInTheDocument();

    expect(screen.getByText(/уверенность высокая/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'правило + модель' }));
    expect(screen.getByText(/цитаты сверены с текстом, вывод сделало правило/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Подробнее' }));
    expect(screen.getByText('Одинаковый перечень во всех разделах.')).toBeInTheDocument();
    expect(screen.getByText('Приведите описание к одному варианту.')).toBeInTheDocument();
    expect(screen.getByText('Требования к системе, 7 семестр')).toBeInTheDocument();

    await user.click(screen.getByText('Сведения о проверке'));
    expect(screen.getByText('правила и языковая модель')).toBeInTheDocument();
    expect(screen.getByText('qwen3.5:4b, Q4_K_M')).toBeInTheDocument();
    expect(screen.getByText('2026.10.18+e9f1e8f4')).toBeInTheDocument();
  });

  it('пустой отчёт: замечаний нет, счётчики нулевые', async () => {
    const report = { ...example('format'), findings: [], requirements: [] };
    show({ report, comparison: null });
    expect(await screen.findByText('Замечаний нет')).toBeInTheDocument();
    expect(screen.getByText('Оформление: 0 · Структура: 0 · Содержание: 0')).toBeInTheDocument();
    expect(screen.queryByText('Не проверено')).toBeNull();
  });

  it('только «не проверено»: причины пропуска словами, без повторов', async () => {
    const base = example('format');
    const skipped = [
      { id: 'requirements', stage: 'claims', reason: 'NO_REQUIREMENTS',
        message: 'Требования к содержанию для 3 семестра пока не заданы — проверены оформление и согласованность текста.' },
      { id: '*', stage: 'claims', reason: 'LLM_UNAVAILABLE', message: 'Языковая модель не отвечает. Содержательная проверка не выполнялась.' },
      { id: 'REQ7-TESTING', stage: 'reasoning', reason: 'LLM_UNAVAILABLE', message: 'Языковая модель не отвечает. Содержательная проверка не выполнялась.' },
    ];
    show({ report: { ...base, findings: [], pipeline: { ...base.pipeline, skipped } }, comparison: null });
    const block = (await screen.findByRole('heading', { name: 'Не проверено' })).closest('section');
    expect(within(block).getAllByRole('listitem')).toHaveLength(2);
    expect(within(block).getByText(/для 3 семестра пока не заданы/)).toBeInTheDocument();
  });

  it('старый формат: отчёта v2 нет — замечания прежним списком', async () => {
    const { api } = show({ report: null, comparison: null }, { work: { format_errors: LEGACY } });
    await waitFor(() => expect(api.find('GET', '/submissions/5/report-v2')).toHaveLength(1));
    expect(screen.getByText('Левое поле 15 мм вместо 30.')).toBeInTheDocument();
    expect(screen.queryByText('Сведения о проверке')).toBeNull();
  });

  it('сбой загрузки отчёта не прячет замечания', async () => {
    show(jsonResponse({ code: 'FORBIDDEN' }, 403), { work: { format_errors: LEGACY } });
    expect(await screen.findByText('Левое поле 15 мм вместо 30.')).toBeInTheDocument();
  });

  it('карта требований: у студента свёрнута, у преподавателя развёрнута, статусы словами', async () => {
    show({ report: example('requirements'), comparison: null });
    const summary = await screen.findByText(/Требования семестра: выполнено \d+ из 9/);
    expect(summary.closest('details').open).toBe(false);
  });

  it('преподаватель: требования развёрнуты, «неверно» сворачивает замечание, повтор снимает отметку', async () => {
    const report = example('requirements');
    const first = report.findings[0];
    const { api, user } = show({ report, feedback: {}, comparison: null }, {
      forTeacher: true,
      routes: {
        [`PUT /teacher/submissions/5/findings/${first.id}/feedback`]: { verdict: 'incorrect' },
        [`DELETE /teacher/submissions/5/findings/${first.id}/feedback`]: new Response(null, { status: 204 }),
      },
    });
    const summary = await screen.findByText(/Требования семестра/);
    expect(summary.closest('details').open).toBe(true);
    expect(screen.getAllByText(/не найдено|выполнено|не на этом этапе|нарушено|спорно/).length).toBeGreaterThan(0);

    const card = screen.getByText(first.message).closest('li');
    await user.click(within(card).getByRole('button', { name: 'Неверно' }));
    await waitFor(() => expect(api.find('PUT', `/teacher/submissions/5/findings/${first.id}/feedback`)[0].body)
      .toEqual({ verdict: 'incorrect' }));
    const dismissed = screen.getByText('Отмечено как неверное').closest('li');
    expect(within(dismissed).getByRole('button', { name: 'Неверно' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(within(dismissed).getByRole('button', { name: 'Неверно' }));
    await waitFor(() => expect(api.find('DELETE', `/teacher/submissions/5/findings/${first.id}/feedback`)).toHaveLength(1));
    expect(screen.queryByText('Отмечено как неверное')).toBeNull();
  });

  it('студент отметок не видит; сохранённая отметка преподавателя видна ему самому', async () => {
    const report = example('contradiction');
    show({ report, comparison: null });
    await screen.findByText(/перечни в разных разделах/);
    expect(screen.queryByRole('button', { name: 'Верно' })).toBeNull();
  });

  it('отметка не сохранилась — возвращается прежнее состояние и текст ошибки', async () => {
    const report = example('contradiction');
    const { user } = show({ report, feedback: { 'f-001': 'correct' }, comparison: null }, { forTeacher: true });
    const correct = await screen.findByRole('button', { name: 'Верно' });
    expect(correct).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Неверно' }));
    expect(await screen.findByText('Не удалось сохранить отметку. Попробуйте ещё раз.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Верно' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('сравнение версий: счётчики над замечаниями, списки — по нажатию', async () => {
    const comparison = {
      previous_submission_id: 4,
      fixed: [{ requirement_id: 'FMT-MARGIN-LEFT', title: 'Левое поле', category: 'format', count_before: 1, count_after: 0 }],
      remaining: [{ requirement_id: 'CNT-CONSIST-002', title: 'Согласованность перечней', category: 'content', count_before: 2, count_after: 1 }],
      new: [],
    };
    const { user } = show({ report: example('contradiction'), comparison });
    const head = await screen.findByRole('button', { name: /Исправлено: 1 · Осталось: 1 · Появилось: 0/ });
    expect(screen.queryByText('Левое поле')).toBeNull();
    await user.click(head);
    expect(screen.getByText('Левое поле')).toBeInTheDocument();
    expect(screen.getByText('Согласованность перечней (было 2, стало 1)')).toBeInTheDocument();
  });
});

describe('ход проверки', () => {
  it('подшаг текущего этапа — словами', () => {
    expect(formatStep({ status: 'PROCESSING', pipeline_stage: 'structure' }).text).toBe('Проверяется: структура');
    expect(formatStep({ status: 'PROCESSING', pipeline_stage: 'extract' }).text).toBe('Проверяется: извлечение текста');
    expect(formatStep({ status: 'PROCESSING' }).text).toBe('Проверяется');
    const content = { status: 'PROCESSING', content_status: 'PENDING', format_errors: '[]' };
    expect(contentStep({ ...content, pipeline_stage: 'claims' }).text).toBe('Проверяется: содержание: модель');
    expect(contentStep({ ...content, pipeline_stage: 'reasoning' }).text).toBe('Проверяется: правила');
    expect(contentStep({ ...content, pipeline_stage: 'format' }).text).toBe('Проверяется');
  });

  it('уверенность — словами по порогам базы знаний', () => {
    expect(confidenceLabel(0.9)).toBe('уверенность высокая');
    expect(confidenceLabel(0.8)).toBe('уверенность средняя');
    expect(confidenceLabel(0.5)).toBe('уверенность низкая, решит преподаватель');
    expect(confidenceLabel(null)).toBe('');
  });
});
