import { useEffect, useLayoutEffect } from 'react';
import './styles/app.css';

import Screen from './components/Screen';
import { useNavigator } from './navigation/useNavigator';
import { useSession } from './features/registration/useSession';
import { useRegistration } from './features/registration/useRegistration';
import RoleScreen from './features/registration/RoleScreen';
import StudentRegisterScreen from './features/registration/StudentRegisterScreen';
import TeacherRegisterScreen from './features/registration/TeacherRegisterScreen';
import ConfirmCodeScreen from './features/registration/ConfirmCodeScreen';
import { useStudentWorkspace } from './features/student/useStudentWorkspace';
import WorksScreen from './features/student/WorksScreen';
import WorkScreen from './features/student/WorkScreen';
import UploadScreen from './features/student/UploadScreen';
import { useTeacherWorkspace } from './features/teacher/useTeacherWorkspace';
import QueueScreen from './features/teacher/QueueScreen';
import TeacherWorkScreen from './features/teacher/TeacherWorkScreen';
import NotesScreen from './features/teacher/NotesScreen';
import { useAdminCalendar } from './features/admin/useAdminCalendar';
import AdminScreen from './features/admin/AdminScreen';
import { useSettings } from './features/settings/useSettings';
import { themeVariables } from './features/settings/customTheme';
import SettingsScreen from './features/settings/SettingsScreen';
import { useHelp } from './features/help/useHelp';
import HelpSheet from './features/help/HelpSheet';
import { paintChrome } from './telegram/webApp';
import { setMainButtonColors } from './telegram/useTelegramButtons';

// Каркас приложения: тема, вход, стек экранов роли. Сами экраны — в features/.
function App() {
  const settings = useSettings();
  const session = useSession();
  const { step, userRole } = session;
  const main = step === 'main';

  const nav = useNavigator({ role: userRole, active: main });
  const help = useHelp({ role: userRole });
  const registration = useRegistration({ session, onRegistered: help.maybeOpen });
  const student = useStudentWorkspace({ active: main && userRole === 'student' });
  const teacher = useTeacherWorkspace({
    active: main && userRole === 'teacher',
    userRole,
    currentUserId: session.currentUserId,
    onOpenQueue: () => nav.home(),
  });
  const admin = useAdminCalendar({ active: main && userRole === 'admin' });

  // Палитра — переменными CSS на корне; шапка Telegram и главная кнопка — в цвет темы.
  const { colors } = settings;
  useEffect(() => {
    document.documentElement.style.backgroundColor = colors.bg;
    document.body.style.backgroundColor = colors.bg;
    paintChrome(colors.bg);
    setMainButtonColors({ color: colors.button, text_color: colors.buttonText });
  }, [colors]);

  // Новый экран открывается сверху.
  const screenKey = nav.current ? `${nav.depth}-${nav.current.screen}-${nav.current.params?.id ?? ''}` : step;
  useLayoutEffect(() => { window.scrollTo?.(0, 0); }, [screenKey]);

  const themeClass = [
    settings.custom.enabled ? 'app-custom' : '',
    settings.dark ? 'app-dark' : '',
    settings.contrast && !settings.custom.enabled ? 'app-contrast' : '',
  ].filter(Boolean).join(' ');

  // Заголовок экрана: «Назад» своей стрелкой, только если нет родной кнопки Telegram; справа — справка и настройки.
  const header = (actions = []) => ({
    onBack: nav.canGoBack && !nav.nativeBack ? nav.back : undefined,
    actions,
  });
  const rootActions = [
    ...(userRole === 'teacher' ? [{ icon: 'notes', label: 'Заметки', onClick: () => nav.push('notes') }] : []),
    { icon: 'help', label: 'Справка', onClick: help.show },
    { icon: 'settings', label: 'Настройки', onClick: () => nav.push('settings') },
  ];

  const renderMain = () => {
    const { screen, params } = nav.current || {};
    switch (screen) {
      case 'works':
        return <WorksScreen works={student.works} me={session.profile} header={header(rootActions)}
          onOpen={(id) => nav.push('work', { id })} onUpload={() => nav.push('upload')} />;
      case 'upload':
        return <UploadScreen me={session.profile} preset={params.preset} header={header()}
          onUploaded={async (id) => {
            await student.works.refresh();
            if (id) nav.replace('work', { id });
            else nav.back();
          }} />;
      case 'work':
        return userRole === 'teacher'
          ? <TeacherWorkScreen key={params.id} id={params.id} teacher={teacher} header={header()} onDecided={nav.back} />
          : <WorkScreen key={params.id} id={params.id} works={student.works} header={header()}
            onSelectVersion={(id) => nav.replace('work', { id })}
            onUploadNew={(preset) => nav.push('upload', { preset })}
            onWithdrawn={nav.back} />;
      case 'queue':
        return <QueueScreen teacher={teacher} header={header(rootActions)} onOpen={(id) => nav.push('work', { id })} />;
      case 'notes':
        return <NotesScreen notes={teacher.notes} header={header()} onFind={teacher.findFromNote} />;
      case 'admin':
        return <AdminScreen admin={admin} header={header(rootActions)} />;
      case 'settings':
        return <SettingsScreen settings={settings} profile={session.profile} header={header()}
          onNeedProfile={session.refreshProfile}
          onChangeRole={userRole === 'admin' ? undefined : registration.reset} />;
      default:
        return null;
    }
  };

  return (
    <div className={`App ${themeClass} font-${settings.fontSize}`} style={themeVariables(colors)}>
      <main className="app-main" key={screenKey}>
        {step === 'loading' && <Screen><p className="screen-note">Загрузка…</p></Screen>}
        {step === 'role' && <RoleScreen registration={registration} registeredRole={session.registeredRole} />}
        {step === 'register' && userRole === 'student' && <StudentRegisterScreen registration={registration} />}
        {step === 'register' && userRole === 'teacher' && <TeacherRegisterScreen registration={registration} />}
        {step === 'confirm_code' && <ConfirmCodeScreen registration={registration} />}
        {main && renderMain()}
      </main>
      {help.open && main && <HelpSheet role={userRole} onClose={help.close} />}
    </div>
  );
}

export default App;
