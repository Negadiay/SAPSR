import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import StudentWorksTab from './StudentWorksTab';

const renderWorks = (submissions) => render(
  <StudentWorksTab works={{ submissions, refresh: vi.fn(), withdrawing: null, requestWithdraw: vi.fn() }} />,
);

const work = (status, extra = {}) => ({
  id: 1, file_name: 'kursach.pdf', status, created_at: '2026-10-18T10:00:00Z', ...extra,
});

describe('Мои работы: статусы проверки', () => {
  it('работа в очереди — понятная подпись и нет кнопки отчёта', () => {
    renderWorks([work('QUEUED')]);
    expect(screen.getByText('В очереди на проверку')).toBeInTheDocument();
    expect(screen.queryByTitle('Скачать отчёт')).not.toBeInTheDocument();
  });

  it('сбой проверки — предлагает загрузить снова, без отчёта и отзыва', () => {
    const { container } = renderWorks([work('FAILED')]);
    expect(screen.getByText('Проверка не удалась — загрузите работу снова')).toBeInTheDocument();
    expect(screen.queryByTitle('Скачать отчёт')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Отозвать работу')).not.toBeInTheDocument();
    expect(container.querySelector('.notif-line')).toHaveClass('notif-error');
  });

  it('на авто-проверке отчёта ещё нет', () => {
    renderWorks([work('PROCESSING')]);
    expect(screen.getByText('На авто-проверке')).toBeInTheDocument();
    expect(screen.queryByTitle('Скачать отчёт')).not.toBeInTheDocument();
  });

  it('у проверенных работ отчёт можно скачать', () => {
    renderWorks([work('SUCCESS'), work('REJECTED', { id: 2, file_name: 'other.pdf' })]);
    expect(screen.getAllByTitle('Скачать отчёт')).toHaveLength(2);
  });
});
