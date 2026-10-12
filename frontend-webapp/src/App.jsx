import { useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import './styles/app.css';

import Screen from './components/Screen';
import ConfirmDialog from './components/ConfirmDialog';
import BottomNav from './navigation/BottomNav';
import { getTabs } from './navigation/tabs';
import { useTabs } from './navigation/useTabs';
import { useKeyboardAwareNav } from './navigation/useKeyboardAwareNav';
import { useSession } from './features/registration/useSession';
import { useRegistration } from './features/registration/useRegistration';
import RoleScreen from './features/registration/RoleScreen';
import StudentRegisterScreen from './features/registration/StudentRegisterScreen';
import TeacherRegisterScreen from './features/registration/TeacherRegisterScreen';
import ConfirmCodeScreen from './features/registration/ConfirmCodeScreen';
import { useStudentWorkspace } from './features/student/useStudentWorkspace';
import UploadTab from './features/student/UploadTab';
import StudentWorksTab from './features/student/StudentWorksTab';
import { useTeacherWorkspace } from './features/teacher/useTeacherWorkspace';
import QueueTab from './features/teacher/QueueTab';
import HistoryTab from './features/teacher/HistoryTab';
import NotesTab from './features/teacher/NotesTab';
import { useSettings } from './features/settings/useSettings';
import SettingsTab from './features/settings/SettingsTab';
import { useTutorial } from './features/tutorial/useTutorial';
import TutorialOverlay from './features/tutorial/TutorialOverlay';

const MotionDiv = motion.div;

const tabVariants = {
  enter:  (d) => ({ x: d > 0 ? 300 : -300, opacity: 0 }),
  center: { x: 0, opacity: 1 },
  exit:   (d) => ({ x: d < 0 ? 300 : -300, opacity: 0 }),
};

// Каркас приложения: связывает блоки из features/ и решает, какой экран показать.
function App() {
  const settings = useSettings();
  const session = useSession();
  const { step, userRole } = session;
  const tabs = getTabs(userRole);

  // Ref для блокировки свайпа во время туториала (избегает stale closure)
  const swipeBlockedRef = useRef(false);
  const { activeTab, direction, showTab, goToTab } = useTabs({
    swipeEnabled: step === 'main', tabCount: tabs.length, swipeBlockedRef,
  });
  const { keyboardOpen, navRef } = useKeyboardAwareNav();

  const tutorial = useTutorial({ userRole, activeTab, showTab });
  useEffect(() => { swipeBlockedRef.current = tutorial.active; }, [tutorial.active]);

  const registration = useRegistration({
    session,
    onRegistered: tutorial.maybeLaunch,
    onLoggedIn: () => showTab(0, 0),
  });
  const student = useStudentWorkspace({ active: step === 'main' && userRole === 'student' });
  const teacher = useTeacherWorkspace({
    active: step === 'main' && userRole === 'teacher',
    userRole,
    currentUserId: session.currentUserId,
    onOpenQueue: () => showTab(0, -1),
  });

  const handleTabChange = (index) => { goToTab(index); student.upload.clearStatus(); };

  const tabId = tabs[activeTab]?.id;

  return (
    <div className={`App ${settings.theme === 'dark' ? 'app-dark' : ''} font-${settings.fontSize} ${settings.contrast ? 'app-contrast' : ''} ${keyboardOpen ? 'keyboard-open' : ''}`}>

      {/* Диалог подтверждения удаления заметки */}
      {teacher.notes.deleteConfirmId !== null && (
        <ConfirmDialog
          title="Удалить заметку?"
          body="Это действие нельзя отменить."
          confirmLabel="Удалить"
          onConfirm={teacher.notes.confirmDelete}
          onCancel={teacher.notes.cancelDelete}
        />
      )}

      {/* Диалог подтверждения отзыва работы */}
      {student.works.withdrawConfirmId !== null && (
        <ConfirmDialog
          title="Отозвать работу?"
          body="Работа исчезнет из списка преподавателя, а уведомление в Telegram будет удалено."
          confirmLabel="Отозвать"
          onConfirm={student.works.confirmWithdraw}
          onCancel={student.works.cancelWithdraw}
        />
      )}

      {/* Кнопка туториала */}
      {step === 'main' && (
        <button className="tutorial-btn" onClick={tutorial.start} title="Справка">
          💡
        </button>
      )}

      {/* Туториал */}
      {tutorial.active && (
        <TutorialOverlay
          steps={tutorial.steps}
          step={tutorial.stepIndex}
          onNext={tutorial.next}
          onSkip={tutorial.skip}
          refs={tutorial.refs}
        />
      )}

      {step !== 'main' && (
        <div className="branding fade-in">
          <img src="cat.jpg" alt="Logo" className="logo" />
          <h1>SAPSR</h1>
        </div>
      )}

      <AnimatePresence custom={direction} mode="wait">
        {step === 'loading' && (
          <Screen key="loading">
            <p className="description">Загрузка...</p>
          </Screen>
        )}

        {step === 'role' && (
          <RoleScreen key="role" registration={registration} registeredRole={session.registeredRole} />
        )}

        {step === 'register' && userRole === 'student' && (
          <StudentRegisterScreen key="reg-student" registration={registration} />
        )}

        {step === 'register' && userRole === 'teacher' && (
          <TeacherRegisterScreen key="reg-teacher" registration={registration} />
        )}

        {step === 'confirm_code' && (
          <ConfirmCodeScreen key="confirm-code" registration={registration} />
        )}

        {step === 'main' && (
          <MotionDiv key={activeTab} custom={direction} variants={tabVariants}
            initial="enter" animate="center" exit="exit"
            transition={{ type: 'spring', stiffness: 520, damping: 36, mass: 0.75 }}
            className="screen main-content"
          >
            {tabId === 'upload' && (
              <UploadTab upload={student.upload} teacherSelectRef={tutorial.refs.teacherSel}
                fileUploadRef={tutorial.refs.fileUpload} submitRef={tutorial.refs.submitBtn} />
            )}
            {tabId === 'works' && <StudentWorksTab works={student.works} />}
            {tabId === 'queue' && (
              <QueueTab queue={teacher.queue} searchRef={tutorial.refs.teacherSearch}
                listRef={tutorial.refs.submissions} />
            )}
            {tabId === 'history' && <HistoryTab history={teacher.history} />}
            {tabId === 'notes' && (
              <NotesTab notes={teacher.notes} onFind={teacher.findFromNote} addNoteRef={tutorial.refs.addNoteBtn} />
            )}
            {tabId === 'settings' && <SettingsTab settings={settings} onLogout={registration.reset} />}
          </MotionDiv>
        )}
      </AnimatePresence>

      {step === 'main' && (
        <BottomNav
          tabs={tabs}
          activeTab={activeTab}
          onSelect={handleTabChange}
          navRef={navRef}
          tutorialRefs={tutorial.refs}
          badgeCount={teacher.queue.pending.length}
        />
      )}
    </div>
  );
}

export default App;
