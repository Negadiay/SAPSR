import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ConfirmDialog from './ConfirmDialog';

const setup = () => {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmDialog title="Удалить заметку?" body="Это действие нельзя отменить."
      confirmLabel="Удалить" onConfirm={onConfirm} onCancel={onCancel} />
  );
  return { onConfirm, onCancel, user: userEvent.setup() };
};

describe('ConfirmDialog', () => {
  it('показывает заголовок, пояснение и подпись кнопки', () => {
    setup();
    expect(screen.getByText('Удалить заметку?')).toBeInTheDocument();
    expect(screen.getByText('Это действие нельзя отменить.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Удалить' })).toBeInTheDocument();
  });

  it('подтверждает по основной кнопке', async () => {
    const { onConfirm, onCancel, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Удалить' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('отменяет по кнопке «Отмена» и по нажатию на фон', async () => {
    const { onConfirm, onCancel, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Отмена' }));
    await user.click(document.querySelector('.confirm-overlay'));
    expect(onCancel).toHaveBeenCalledTimes(2);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('нажатие внутри окна его не закрывает', async () => {
    const { onCancel, user } = setup();
    await user.click(screen.getByText('Это действие нельзя отменить.'));
    expect(onCancel).not.toHaveBeenCalled();
  });
});
