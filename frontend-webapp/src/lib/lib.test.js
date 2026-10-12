import { describe, it, expect } from 'vitest';
import { matchesSearch } from './fuzzySearch';
import { formatCountdown, formatDate } from './format';
import { groupByFileName } from './submissions';

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

describe('formatDate', () => {
  it('для пустого значения возвращает пустую строку', () => {
    expect(formatDate('')).toBe('');
    expect(formatDate(undefined)).toBe('');
  });

  it('форматирует дату и время по-русски', () => {
    expect(formatDate('2026-10-02T10:00:00')).toBe('02.10.2026 10:00');
  });
});

describe('groupByFileName', () => {
  const submissions = [
    { id: 1, file_name: 'Kursach.pdf', created_at: '2026-10-01T10:00:00Z' },
    { id: 2, file_name: 'other.pdf', created_at: '2026-09-01T10:00:00Z' },
    { id: 3, file_name: 'kursach.PDF', created_at: '2026-10-03T10:00:00Z' },
  ];

  it('объединяет версии по названию файла без учёта регистра и расширения', () => {
    const groups = groupByFileName(submissions);
    expect(groups).toHaveLength(2);
    expect(groups[0].items.map(s => s.id)).toEqual([3, 1]);
  });

  it('ставит последнюю версию первой и сортирует группы по ней', () => {
    const groups = groupByFileName(submissions);
    expect(groups.map(g => g.latest.id)).toEqual([3, 2]);
  });

  it('называет группу по первому встреченному файлу', () => {
    expect(groupByFileName(submissions)[0].fileName).toBe('Kursach.pdf');
  });

  it('для пустого списка возвращает пустой', () => {
    expect(groupByFileName([])).toEqual([]);
  });
});
