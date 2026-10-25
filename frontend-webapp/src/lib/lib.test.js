import { describe, it, expect } from 'vitest';
import { matchesSearch } from './fuzzySearch';
import { count, formatCountdown, formatDate, formatDay } from './format';
import { chainOf, groupVersions } from './submissions';
import { contentStep, formatStep, teacherStep, workStatus } from './status';
import { splitFindings, summarizeFindings } from './findings';

describe('matchesSearch', () => {
  const fields = ['Кузнецова А.А. (гр. 321701)', 'zapiska.pdf', '2026-10-02T10:00:00Z'];

  it('пустой запрос подходит всем', () => {
    expect(matchesSearch('', fields)).toBe(true);
    expect(matchesSearch('   ', fields)).toBe(true);
  });

  it('находит по части имени без учёта регистра', () => {
    expect(matchesSearch('кузнец', fields)).toBe(true);
    expect(matchesSearch('ZAPISKA', fields)).toBe(true);
  });

  it('прощает опечатку в фамилии', () => {
    expect(matchesSearch('кузнецва', fields)).toBe(true);
    expect(matchesSearch('петров', fields)).toBe(false);
  });

  it('номер группы ищет строго', () => {
    expect(matchesSearch('321701', fields)).toBe(true);
    expect(matchesSearch('321702', fields)).toBe(false);
  });

  it('требует совпадения всех слов запроса', () => {
    expect(matchesSearch('кузнецова 321701', fields)).toBe(true);
    expect(matchesSearch('кузнецова 321702', fields)).toBe(false);
  });

  it('пропускает пустые поля', () => {
    expect(matchesSearch('иванов', [undefined, null, 'Иванов И.И.'])).toBe(true);
  });
});

describe('formatCountdown', () => {
  it('показывает минуты и секунды', () => {
    expect(formatCountdown(125)).toBe('2:05');
    expect(formatCountdown(59)).toBe('0:59');
  });

  it('не уходит в минус', () => {
    expect(formatCountdown(-3)).toBe('0:00');
  });
});

describe('formatDate — всегда по Минску', () => {
  const now = new Date('2026-10-25T12:00:00+03:00');

  it('для пустого значения возвращает пустую строку', () => {
    expect(formatDate('')).toBe('');
    expect(formatDate(undefined)).toBe('');
  });

  it('время сервера без пояса показывает как есть, без перевода в пояс телефона', () => {
    expect(formatDate('2026-10-02T10:00:00')).toBe('02.10.2026, 10:00');
    expect(formatDate('2026-10-02T10:00:00.123456')).toBe('02.10.2026, 10:00');
  });

  it('время с поясом переводит в минское', () => {
    expect(formatDate('2026-10-02T07:00:00Z')).toBe('02.10.2026, 10:00');
    expect(formatDate('2026-10-02T09:00:00+02:00')).toBe('02.10.2026, 10:00');
  });

  it('в текущем году можно без года', () => {
    expect(formatDate('2026-10-02T10:00:00', { short: true, now })).toBe('02.10, 10:00');
    expect(formatDate('2025-10-02T10:00:00', { short: true, now })).toBe('02.10.2025, 10:00');
    expect(formatDay('2026-12-26', { short: true, now })).toBe('26.12');
    expect(formatDay('2026-12-26')).toBe('26.12.2026');
  });
});

describe('count', () => {
  it('склоняет по числу', () => {
    expect(count(1, 'версия', 'версии', 'версий')).toBe('1 версия');
    expect(count(3, 'версия', 'версии', 'версий')).toBe('3 версии');
    expect(count(5, 'версия', 'версии', 'версий')).toBe('5 версий');
    expect(count(12, 'версия', 'версии', 'версий')).toBe('12 версий');
    expect(count(22, 'версия', 'версии', 'версий')).toBe('22 версии');
  });
});

describe('groupVersions — по цепочке parent_id, а не по имени файла', () => {
  const submissions = [
    { id: 3, parent_id: 1, file_name: 'Новое имя.pdf', created_at: '2026-10-03T10:00:00' },
    { id: 2, file_name: 'Курсовая.pdf', created_at: '2026-09-01T10:00:00' },
    { id: 1, file_name: 'Курсовая.pdf', created_at: '2026-10-01T10:00:00' },
  ];

  it('версии одной работы — вместе, даже с другим именем файла', () => {
    const groups = groupVersions(submissions);
    expect(groups.map((g) => g.items.map((s) => s.id))).toEqual([[3, 1], [2]]);
  });

  it('разные работы с одинаковым именем не склеиваются', () => {
    expect(groupVersions(submissions).map((g) => g.latest.id)).toEqual([3, 2]);
  });

  it('цепочку можно найти по любой версии', () => {
    expect(chainOf(submissions, 1).latest.id).toBe(3);
    expect(groupVersions([])).toEqual([]);
  });
});

describe('состояние работы', () => {
  const FINDINGS = JSON.stringify([
    { severity: 'major', category: 'format', origin: 'symbolic', message: 'Левое поле' },
    { severity: 'minor', category: 'format', origin: 'symbolic', message: 'Интервал' },
    { severity: 'major', category: 'content', origin: 'hybrid', needs_teacher_review: true, message: 'Противоречие' },
  ]);

  it('одной строкой для списка: решение преподавателя важнее итога проверки', () => {
    expect(workStatus({ status: 'REJECTED', teacher_verdict: 'REVISION' })).toEqual({ tone: 'warning', label: 'На доработке' });
    expect(workStatus({ status: 'REJECTED', format_errors: FINDINGS }).label).toBe('Ошибки в оформлении: 1');
    expect(workStatus({ status: 'PROCESSING', content_status: 'PENDING' }).label).toBe('Проверяется содержание');
    expect(workStatus({ status: 'QUEUED' }).label).toBe('В очереди');
  });

  it('по шагам: оформление, содержание, преподаватель', () => {
    const work = { status: 'REJECTED', content_status: 'DONE', format_errors: FINDINGS };
    expect(formatStep(work)).toEqual({ tone: 'error', text: 'Ошибок: 1, предупреждений: 1' });
    expect(contentStep(work)).toEqual({ tone: 'warning', text: '1 замечание, на решение преподавателя: 1' });
    expect(teacherStep(work)).toEqual({ tone: 'pending', text: 'Ждёт решения' });
  });

  it('сбой — словами, без кода', () => {
    const failed = { status: 'FAILED', failure_code: 'PDF_ENCRYPTED' };
    expect(formatStep(failed)).toEqual({ tone: 'error', text: 'Файл защищён паролем.' });
    expect(contentStep(failed).tone).toBe('muted');
  });

  it('содержание не проверено — объясняет почему', () => {
    expect(contentStep({ status: 'SUCCESS', content_status: 'TIMEOUT' }).text).toBe('Не проверено: не хватило времени');
  });

  it('замечания делятся на оформление (сначала ошибки) и содержание', () => {
    const { format, content } = splitFindings(FINDINGS);
    expect(format.map((f) => f.message)).toEqual(['Левое поле', 'Интервал']);
    expect(content.map((f) => f.message)).toEqual(['Противоречие']);
    expect(summarizeFindings('не JSON')).toEqual({ formatErrors: 0, formatWarnings: 0, content: [], needsReview: 0 });
  });
});
