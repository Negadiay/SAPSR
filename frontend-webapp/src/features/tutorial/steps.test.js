import { describe, it, expect } from 'vitest';
import { getTutorialSteps, getTutorialTabIndex } from './steps';
import { getTabs } from '../../navigation/tabs';

describe.each(['student', 'teacher'])('обучение для роли %s', (role) => {
  const steps = getTutorialSteps(role);
  const tabs = getTabs(role);

  it('каждый подсвечиваемый элемент привязан к существующей вкладке', () => {
    steps.filter(step => step.refKey).forEach((step) => {
      const tabIndex = getTutorialTabIndex(role, step.refKey);
      expect(tabIndex, step.refKey).not.toBeNull();
      expect(tabs[tabIndex], step.refKey).toBeDefined();
    });
  });

  it('кнопки навигации из шагов есть в нижней панели и ведут на свою вкладку', () => {
    tabs.filter(tab => tab.tutorialRef).forEach((tab) => {
      expect(steps.some(step => step.refKey === tab.tutorialRef), tab.tutorialRef).toBe(true);
      expect(getTutorialTabIndex(role, tab.tutorialRef)).toBe(tabs.indexOf(tab));
    });
  });

  it('у каждого шага есть текст', () => {
    steps.forEach(step => expect(step.text.length).toBeGreaterThan(0));
  });

  it('настройки — последняя вкладка', () => {
    expect(tabs.at(-1).id).toBe('settings');
  });
});

it('шаг без подсветки не переключает вкладку', () => {
  expect(getTutorialTabIndex('student', null)).toBeNull();
  expect(getTutorialTabIndex('teacher', null)).toBeNull();
});
