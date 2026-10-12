import { useState, useRef } from 'react';
import { getTutorialSteps, getTutorialTabIndex } from './steps';

const onboardedKey = (role) => 'sapsr_onboarded_' + role;

export function useTutorial({ userRole, activeTab, showTab }) {
  const [active, setActive] = useState(false);
  const [stepIndex, setStepIndex] = useState(0);

  // Элементы интерфейса, которые подсвечивает обучение
  const refs = {
    teacherSel:    useRef(null),
    fileUpload:    useRef(null),
    submitBtn:     useRef(null),
    navNotif:      useRef(null),
    navSettings:   useRef(null),
    navNotes:      useRef(null),
    navHistory:    useRef(null),
    submissions:   useRef(null),
    teacherSearch: useRef(null),
    addNoteBtn:    useRef(null),
  };

  const steps = getTutorialSteps(userRole);

  const openStep = (nextStep) => {
    const tabIndex = getTutorialTabIndex(userRole, steps[nextStep]?.refKey);
    if (tabIndex !== null && tabIndex !== activeTab) {
      showTab(tabIndex, tabIndex > activeTab ? 1 : -1);
    }
    setStepIndex(nextStep);
  };

  const start = () => {
    showTab(0, -1);
    setStepIndex(0);
    setActive(true);
  };

  const skip = () => {
    setActive(false);
    localStorage.setItem(onboardedKey(userRole), '1');
  };

  const next = () => {
    if (stepIndex + 1 >= steps.length) skip();
    else openStep(stepIndex + 1);
  };

  // Первый вход под ролью: обучение запускается само
  const maybeLaunch = (role) => {
    if (!localStorage.getItem(onboardedKey(role))) {
      setTimeout(start, 600);
    }
  };

  return { active, stepIndex, steps, refs, start, next, skip, maybeLaunch };
}
