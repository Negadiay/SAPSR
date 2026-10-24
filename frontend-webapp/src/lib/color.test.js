import { describe, it, expect } from 'vitest';
import { contrastRatio, hexToHsv, hsvToHex, normalizeHex, readableOn, withAlpha } from './color';

describe('цвета своей темы', () => {
  it('код HEX — с решёткой или без, три или шесть цифр; иное не принимается', () => {
    expect(normalizeHex('#2E8D8A')).toBe('#2e8d8a');
    expect(normalizeHex(' 2e8d8a ')).toBe('#2e8d8a');
    expect(normalizeHex('#abc')).toBe('#aabbcc');
    expect(normalizeHex('#2e8d8')).toBeNull();
    expect(normalizeHex('зелёный')).toBeNull();
    expect(normalizeHex(undefined)).toBeNull();
  });

  it('круг: основные оттенки на своих углах, туда и обратно без потерь', () => {
    expect(hexToHsv('#ff0000')).toEqual({ h: 0, s: 1, v: 1 });
    expect(hexToHsv('#00ff00').h).toBe(120);
    expect(hexToHsv('#0000ff').h).toBe(240);
    expect(hsvToHex({ h: 60, s: 1, v: 1 })).toBe('#ffff00');
    expect(hsvToHex({ h: 0, s: 0, v: 0.5 })).toBe('#808080');
    for (const hex of ['#2e8d8a', '#212d2f', '#ff8c00', '#ffffff', '#000000']) {
      expect(hsvToHex(hexToHsv(hex))).toBe(hex);
    }
  });

  it('контраст по WCAG: чёрный на белом — 21, одинаковые — 1', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(21, 5);
    expect(contrastRatio('#2e8d8a', '#2e8d8a')).toBe(1);
    expect(contrastRatio('#767676', '#ffffff')).toBeCloseTo(4.54, 2);
  });

  it('текст на цветной плашке — белый или чёрный, что читается лучше', () => {
    expect(readableOn('#d32f2f')).toBe('#ffffff');
    expect(readableOn('#ffb84d')).toBe('#111111');
    expect(withAlpha('#2e8d8a', 0.2)).toBe('rgba(46, 141, 138, 0.2)');
  });
});
