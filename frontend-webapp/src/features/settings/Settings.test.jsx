import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../../App';
import ColorWheel from '../../components/ColorWheel';
import { mockApi, stubTelegram } from '../../test/mockApi';
import { PRESETS, presetFor, readabilityIssues, sanitizeColors, themeVariables } from './customTheme';

const studentRoutes = {
  'GET /me': { role: 'STUDENT', telegram_id: 42, full_name: 'Терешкова А.В. (гр. 321702)', last_name: 'Терешкова', group: '321702', course: 4 },
  'GET /teachers': [],
  'GET /submissions': [],
};

const start = async (telegram = {}) => {
  stubTelegram(telegram);
  mockApi(studentRoutes);
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('heading', { name: 'Мои работы' });
  return user;
};

const openSettings = async (telegram) => {
  const user = await start(telegram);
  await user.click(screen.getByTitle('Настройки'));
  await screen.findByRole('heading', { name: 'Настройки' });
  return user;
};

const app = () => document.querySelector('.App');
const variable = (name) => app().style.getPropertyValue(name);

describe('тема', () => {
  it('по умолчанию — как в Telegram: тёмный Telegram — тёмное приложение', async () => {
    await start({ colorScheme: 'dark' });
    expect(app()).toHaveClass('app-dark');
    expect(variable('--bg')).toBe(PRESETS.dark.bg);
  });

  it('светлую и тёмную можно выбрать вручную, выбор запоминается', async () => {
    const user = await openSettings({ colorScheme: 'dark', platform: 'android' });
    expect(screen.getByRole('radio', { name: 'Как в Telegram' })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: 'Светлая' }));
    expect(app()).not.toHaveClass('app-dark');
    expect(localStorage.getItem('sapsr_theme_mode')).toBe('light');
  });

  it('прежний выбор «ночного режима» сохраняется, а прежняя «светлая» становится «как в Telegram»', async () => {
    localStorage.setItem('sapsr_theme', 'dark');
    await start({ colorScheme: 'light' });
    expect(app()).toHaveClass('app-dark');
  });

  it('высокий контраст и размер текста', async () => {
    const user = await openSettings();
    await user.click(screen.getByRole('switch', { name: 'Высокий контраст' }));
    expect(app()).toHaveClass('app-contrast');
    expect(variable('--text')).toBe(PRESETS.contrastLight.text);

    fireEvent.change(screen.getByRole('slider', { name: 'Размер текста' }), { target: { value: '3' } });
    expect(app()).toHaveClass('font-xlarge');
    expect(screen.getByText('Очень крупный')).toBeInTheDocument();
  });

  it('в профиле — «Фамилия И. О.», группа и курс', async () => {
    await openSettings();
    expect(screen.getByText('Терешкова А. В.')).toBeInTheDocument();
    expect(screen.getByText('Группа 321702 · 4 курс')).toBeInTheDocument();
  });
});

describe('своя тема: цвета', () => {
  it('основы — цвета встроенных тем, и все они читаются', () => {
    expect(presetFor(false, false)).toBe(PRESETS.light);
    expect(presetFor(true, true)).toBe(PRESETS.contrastDark);
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
    const vars = themeVariables({ ...PRESETS.dark, error: '#ff8080' });
    expect(vars['--bg']).toBe(PRESETS.dark.bg);
    expect(vars['--on-error']).toBe('#111111');
  });
});

describe('своя тема: настройки', () => {
  it('начинается с текущих цветов и заменяет тему и контраст', async () => {
    localStorage.setItem('sapsr_theme_mode', 'dark');
    const user = await openSettings();
    expect(app()).toHaveClass('app-dark');

    await user.click(screen.getByRole('switch', { name: 'Своя тема' }));
    expect(app()).toHaveClass('app-custom');
    expect(variable('--bg')).toBe(PRESETS.dark.bg);
    expect(screen.getByRole('radio', { name: 'Светлая' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Высокий контраст' })).toBeDisabled();
    // Цвета раскрываются сразу.
    expect(screen.getByLabelText('Фон')).toHaveValue(PRESETS.dark.bg);

    await user.click(screen.getByRole('switch', { name: 'Своя тема' }));
    expect(app()).not.toHaveClass('app-custom');
  });

  it('цвет задаётся кодом HEX; ошибка в коде не применяется и откатывается', async () => {
    const user = await openSettings();
    await user.click(screen.getByRole('switch', { name: 'Своя тема' }));

    const bg = screen.getByLabelText('Фон');
    await user.clear(bg);
    await user.type(bg, '#FFF8E7');
    expect(variable('--bg')).toBe('#fff8e7');
    expect(JSON.parse(localStorage.getItem('sapsr_custom_theme')).colors.bg).toBe('#fff8e7');

    const button = screen.getByLabelText('Кнопки');
    await user.clear(button);
    await user.type(button, '#12');
    expect(button).toHaveAttribute('aria-invalid', 'true');
    expect(variable('--accent')).toBe(PRESETS.light.button);
    await user.tab();
    expect(button).toHaveValue(PRESETS.light.button);

    await user.clear(button);
    await user.type(button, 'c50');
    await user.tab();
    expect(button).toHaveValue('#cc5500');
    expect(variable('--accent')).toBe('#cc5500');
  });

  it('предупреждает, если текст сливается с фоном, и основа возвращает всё одним нажатием', async () => {
    const user = await openSettings();
    await user.click(screen.getByRole('switch', { name: 'Своя тема' }));

    const text = screen.getByLabelText('Текст');
    await user.clear(text);
    await user.type(text, '#e8f4f0');
    expect(screen.getByRole('status')).toHaveTextContent('Текст на фоне плохо читается');
    // Раздел темы при этом пишется чёрным или белым — иначе и предупреждение не прочесть.
    const section = screen.getByText('Тема').closest('.settings-card');
    expect(section).toHaveClass('theme-safe');
    expect(variable('--safe-text')).toBe('#111111');

    await user.click(screen.getByRole('button', { name: 'Тёмная' }));
    expect(screen.queryByRole('status')).toBeNull();
    expect(section).not.toHaveClass('theme-safe');
    expect(variable('--text')).toBe(PRESETS.dark.text);
  });

  it('цветовой круг открывается у выбранного цвета', async () => {
    const user = await openSettings();
    await user.click(screen.getByRole('switch', { name: 'Своя тема' }));
    await user.click(screen.getByRole('button', { name: 'Кнопки: цветовой круг' }));
    const wheel = screen.getByRole('slider', { name: 'Кнопки: оттенок и насыщенность' });
    wheel.focus();
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}');
    const changed = variable('--accent');
    expect(changed).not.toBe(PRESETS.light.button);
    expect(screen.getByLabelText('Кнопки')).toHaveValue(changed);
  });

  it('при запуске своя тема берётся из сохранённой', () => {
    localStorage.setItem('sapsr_custom_theme', JSON.stringify({ enabled: true, colors: { ...PRESETS.light, bg: '#fff8e7' } }));
    stubTelegram();
    mockApi(studentRoutes);
    render(<App />);
    expect(app()).toHaveClass('app-custom');
    expect(variable('--bg')).toBe('#fff8e7');
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
