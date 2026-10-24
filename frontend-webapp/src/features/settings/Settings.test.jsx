import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../../App';
import ColorWheel from '../../components/ColorWheel';
import { mockApi, stubTelegram } from '../../test/mockApi';
import { PRESETS, presetFor, readabilityIssues, sanitizeColors, themeVariables } from './customTheme';

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }) => children,
  motion: { div: ({ className, children }) => <div className={className}>{children}</div> },
}));

const studentRoutes = {
  'GET /me': { role: 'STUDENT', telegram_id: 42 },
  'GET /teachers': [],
  'GET /submissions': [],
};

const openSettings = async () => {
  stubTelegram();
  mockApi(studentRoutes);
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('heading', { name: 'Загрузка работы' });
  await user.click(within(document.querySelector('.bottom-nav')).getByRole('button', { name: 'Настройки' }));
  return user;
};

const app = () => document.querySelector('.App');
const variable = (name) => app().style.getPropertyValue(name);

describe('своя тема: цвета', () => {
  it('основы — цвета встроенных тем, и все они читаются', () => {
    expect(presetFor('light', false)).toBe(PRESETS.light);
    expect(presetFor('dark', true)).toBe(PRESETS.contrastDark);
    for (const colors of Object.values(PRESETS)) expect(readabilityIssues(colors)).toEqual([]);
  });

  it('плохо читаемое сочетание называется с контрастом', () => {
    const issues = readabilityIssues({ ...PRESETS.light, text: '#e8f4f0' });
    expect(issues.map((i) => i.what)).toEqual(['Текст на фоне', 'Текст на карточках']);
    expect(issues[0].ratio).toBeLessThan(1.5);
  });

  it('испорченные сохранённые цвета заменяются цветами светлой основы', () => {
    expect(sanitizeColors({ bg: '#123', text: 'красный' })).toEqual({ ...PRESETS.light, bg: '#112233' });
  });

  it('переменные: цвета как есть, подписи на плашках — читаемые', () => {
    const vars = themeVariables({ ...PRESETS.dark, nav: '#ffffff' });
    expect(vars['--tg-theme-bg-color']).toBe('#212d2f');
    expect(vars['--app-on-nav']).toBe('rgba(17, 17, 17, 0.8)');
    expect(vars['--app-on-error']).toBe('#111111');
  });
});

describe('своя тема: настройки', () => {
  it('включается под контрастным режимом, начинается с текущих цветов и заменяет ночной и контрастный', async () => {
    localStorage.setItem('sapsr_theme', 'dark');
    const user = await openSettings();
    expect(app()).toHaveClass('app-dark');

    await user.click(screen.getByRole('button', { name: 'Переключить свою тему' }));
    expect(app()).toHaveClass('app-custom');
    expect(app()).not.toHaveClass('app-dark');
    expect(variable('--tg-theme-bg-color')).toBe('#212d2f');
    expect(screen.getByRole('button', { name: 'Переключить ночной режим' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Переключить контрастный режим' })).toBeDisabled();
    // Цвета раскрываются сразу.
    expect(screen.getByLabelText('Фон')).toHaveValue('#212d2f');

    await user.click(screen.getByRole('button', { name: 'Переключить свою тему' }));
    expect(app()).toHaveClass('app-dark');
    expect(variable('--tg-theme-bg-color')).toBe('');
  });

  it('цвет задаётся кодом HEX; ошибка в коде не применяется и откатывается', async () => {
    const user = await openSettings();
    await user.click(screen.getByRole('button', { name: 'Переключить свою тему' }));

    const bg = screen.getByLabelText('Фон');
    await user.clear(bg);
    await user.type(bg, '#FFF8E7');
    expect(variable('--tg-theme-bg-color')).toBe('#fff8e7');
    expect(JSON.parse(localStorage.getItem('sapsr_custom_theme')).colors.bg).toBe('#fff8e7');

    const button = screen.getByLabelText('Кнопки');
    await user.clear(button);
    await user.type(button, '#12');
    expect(button).toHaveAttribute('aria-invalid', 'true');
    expect(variable('--tg-theme-button-color')).toBe('#2e8d8a');
    await user.tab();
    expect(button).toHaveValue('#2e8d8a');

    await user.clear(button);
    await user.type(button, 'c50');
    await user.tab();
    expect(button).toHaveValue('#cc5500');
    expect(variable('--tg-theme-button-color')).toBe('#cc5500');
  });

  it('предупреждает, если текст сливается с фоном, и основа возвращает всё одним нажатием', async () => {
    const user = await openSettings();
    await user.click(screen.getByRole('button', { name: 'Переключить свою тему' }));

    const text = screen.getByLabelText('Текст');
    await user.clear(text);
    await user.type(text, '#e8f4f0');
    expect(screen.getByRole('status')).toHaveTextContent('Текст на фоне читается плохо');
    // Раздел темы при этом пишется чёрным или белым — иначе и предупреждение не прочесть.
    const section = screen.getByText('Тема оформления').closest('.settings-section');
    expect(section).toHaveClass('theme-safe');
    expect(variable('--app-safe-text')).toBe('#111111');

    await user.click(screen.getByRole('button', { name: 'Тёмная' }));
    expect(screen.queryByRole('status')).toBeNull();
    expect(section).not.toHaveClass('theme-safe');
    expect(variable('--tg-theme-text-color')).toBe('#d1dbd8');
    expect(screen.getByLabelText('Текст')).toHaveValue('#d1dbd8');
  });

  it('цветовой круг открывается у выбранного цвета; своя тема сохраняется до следующего запуска', async () => {
    const user = await openSettings();
    await user.click(screen.getByRole('button', { name: 'Переключить свою тему' }));
    await user.click(screen.getByRole('button', { name: 'Кнопки: цветовой круг' }));
    const wheel = screen.getByRole('slider', { name: 'Кнопки: оттенок и насыщенность' });
    wheel.focus();
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}');
    const changed = variable('--tg-theme-button-color');
    expect(changed).not.toBe('#2e8d8a');
    expect(screen.getByLabelText('Кнопки')).toHaveValue(changed);
  });

  it('при запуске своя тема берётся из сохранённой', () => {
    localStorage.setItem('sapsr_custom_theme', JSON.stringify({ enabled: true, colors: { ...PRESETS.light, bg: '#fff8e7' } }));
    stubTelegram();
    mockApi(studentRoutes);
    render(<App />);
    expect(app()).toHaveClass('app-custom');
    expect(variable('--tg-theme-bg-color')).toBe('#fff8e7');
    expect(document.documentElement.style.backgroundColor).toBe('rgb(255, 248, 231)');
  });
});

// Круг на экране управляемый: значение хранит родитель (настройки).
function Controlled({ initial, onChange }) {
  const [value, setValue] = useState(initial);
  return <ColorWheel value={value} label="Фон" onChange={(hex) => { setValue(hex); onChange(hex); }} />;
}

describe('цветовой круг', () => {
  it('стрелки меняют оттенок и насыщенность, ползунок — яркость', () => {
    const onChange = vi.fn();
    render(<Controlled initial="#ff0000" onChange={onChange} />);
    const wheel = screen.getByRole('slider', { name: 'Фон: оттенок и насыщенность' });

    fireEvent.keyDown(wheel, { key: 'ArrowRight', shiftKey: true });
    expect(onChange).toHaveBeenLastCalledWith('#ff4000');
    fireEvent.keyDown(wheel, { key: 'ArrowDown' });
    expect(onChange).toHaveBeenLastCalledWith('#ff490d');

    fireEvent.change(screen.getByRole('slider', { name: 'Фон: яркость' }), { target: { value: '50' } });
    expect(onChange).toHaveBeenLastCalledWith('#802506');
  });

  it('нажатие на круг: угол — оттенок, расстояние от центра — насыщенность', () => {
    const onChange = vi.fn();
    render(<Controlled initial="#ffffff" onChange={onChange} />);
    const wheel = screen.getByRole('slider', { name: 'Фон: оттенок и насыщенность' });
    wheel.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 200 });

    // Справа на краю — 90°, жёлто-зелёный.
    fireEvent.pointerDown(wheel, { clientX: 200, clientY: 100, pointerId: 1 });
    expect(onChange).toHaveBeenLastCalledWith('#80ff00');
    // Центр — без насыщенности, белый.
    fireEvent.pointerMove(wheel, { clientX: 100, clientY: 100, pointerId: 1 });
    expect(onChange).toHaveBeenLastCalledWith('#ffffff');
  });
});
