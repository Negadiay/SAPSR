import { useState, useEffect, useRef, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import './App.css';

const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1';

// --- Туториал ---
const STUDENT_STEPS = [
  { refKey: null,           text: 'Добро пожаловать в SAPSR! Это система автоматической проверки оформления курсовых работ по стандартам БГУИР.' },
  { refKey: 'teacherSel',  text: 'Выберите вашего научного руководителя из списка. Показаны только преподаватели вашей группы.' },
  { refKey: 'fileUpload',  text: 'Прикрепите PDF-файл курсовой работы.' },
  { refKey: 'submitBtn',   text: 'Нажмите «Отправить» — система автоматически проверит оформление по ГОСТу.' },
  { refKey: 'navNotif',    text: 'Во вкладке «Мои работы» отображаются результаты проверки и вердикт преподавателя.' },
  { refKey: 'navSettings', text: 'В настройках можно сменить тему и включить режим для слабовидящих.' },
];
const TEACHER_STEPS = [
  { refKey: null,           text: 'Добро пожаловать! Здесь вы проверяете курсовые работы студентов.' },
  { refKey: 'submissions',  text: 'Здесь появляются работы, прошедшие автоматическую проверку оформления.' },
  { refKey: null,           text: 'Вы получите уведомление в Telegram, когда студент пришлёт работу на проверку.' },
  { refKey: 'navSettings',  text: 'В настройках можно сменить тему и включить режим для слабовидящих.' },
];

function TutorialOverlay({ steps, step, onNext, refs }) {
  const current = steps[step];
  const targetRef = current?.refKey ? refs[current.refKey] : null;
  const [rect, setRect] = useState(null);

  useEffect(() => {
    if (targetRef?.current) {
      setRect(targetRef.current.getBoundingClientRect());
    } else {
      setRect(null);
    }
  }, [step, targetRef]);

  const PAD = 6;
  return (
    <div className="tutorial-overlay" onClick={onNext}>
      {rect && (
        <div className="tutorial-spotlight" style={{
          top:    rect.top    - PAD,
          left:   rect.left   - PAD,
          width:  rect.width  + PAD * 2,
          height: rect.height + PAD * 2,
        }} />
      )}
      <div className="tutorial-tooltip" style={rect
        ? { top: rect.bottom + PAD + 12, left: Math.max(12, rect.left) }
        : { top: '40%', left: '50%', transform: 'translateX(-50%)' }
      }>
        <p>{current?.text}</p>
        <span className="tutorial-counter">{step + 1} / {steps.length}</span>
        <span className="tutorial-hint">Нажмите в любое место →</span>
      </div>
    </div>
  );
}

function App() {
  const [step, setStep]               = useState('loading');
  const [activeTab, setActiveTab]     = useState(0);
  const [direction, setDirection]     = useState(0);
  const [userRole, setUserRole]       = useState('');

  // Регистрация студента
  const [regInput, setRegInput]       = useState('');
  const [regError, setRegError]       = useState('');
  const [registering, setRegistering] = useState(false);

  // Регистрация преподавателя
  const [teacherFullName, setTeacherFullName] = useState('');
  const [teacherEmail, setTeacherEmail]       = useState('');
  const [regCode, setRegCode]                 = useState('');
  const [sendingCode, setSendingCode]         = useState(false);

  // Загрузка файла (студент)
  const [file, setFile]                   = useState(null);
  const [status, setStatus]               = useState('');
  const [teachers, setTeachers]           = useState([]);
  const [selectedTeacherId, setSelectedTeacherId] = useState('');
  const [uploading, setUploading]         = useState(false);
  const [submissions, setSubmissions]     = useState([]);

  // Дашборд преподавателя
  const [teacherSubmissions, setTeacherSubmissions] = useState([]);
  const [expandedId, setExpandedId]       = useState(null);
  const [revisionId, setRevisionId]       = useState(null);
  const [revisionComment, setRevisionComment] = useState('');
  const [verdictLoading, setVerdictLoading] = useState(false);

  // Настройки
  const [theme, setTheme]         = useState(() => localStorage.getItem('sapsr_theme') || 'light');
  const [accessible, setAccessible] = useState(() => localStorage.getItem('sapsr_a11y') === '1');

  // Туториал
  const [tutorialActive, setTutorialActive] = useState(false);
  const [tutorialStep, setTutorialStep]     = useState(0);

  // Refs для туториала
  const refs = {
    teacherSel:  useRef(null),
    fileUpload:  useRef(null),
    submitBtn:   useRef(null),
    navNotif:    useRef(null),
    navSettings: useRef(null),
    submissions: useRef(null),
  };

  const tg = window.Telegram?.WebApp;
  const initData = tg?.initData || '';
  const apiHeaders = (extra = {}) => ({ 'Authorization': initData, ...extra });

  useEffect(() => {
    tg?.ready();
    tg?.expand();
    checkAuth();
  }, []);

  // Применяем тему
  useEffect(() => {
    localStorage.setItem('sapsr_theme', theme);
  }, [theme]);
  useEffect(() => {
    localStorage.setItem('sapsr_a11y', accessible ? '1' : '0');
  }, [accessible]);

  const checkAuth = async () => {
    try {
      const res = await fetch(`${API_BASE}/me`, { headers: apiHeaders() });
      if (res.ok) {
        const data = await res.json();
        if (data.role && data.role !== 'NONE') {
          const role = data.role.toLowerCase();
          setUserRole(role);
          setStep('main');
          if (role === 'student') { fetchTeachers(); fetchSubmissions(); }
          else fetchTeacherSubmissions();
          return;
        }
      }
    } catch (err) { console.warn('Auth error:', err); }
    setStep('role');
  };

  const fetchTeachers = async () => {
    try {
      const res = await fetch(`${API_BASE}/teachers`, { headers: apiHeaders() });
      if (res.ok) {
        const data = await res.json();
        setTeachers(data);
        if (data.length > 0) setSelectedTeacherId(String(data[0].telegram_id || data[0].id || 0));
      }
    } catch (err) { console.warn('Teachers error:', err); }
  };

  const fetchSubmissions = async () => {
    try {
      const res = await fetch(`${API_BASE}/submissions`, { headers: apiHeaders() });
      if (res.ok) setSubmissions(await res.json());
    } catch (err) { console.warn('Submissions error:', err); }
  };

  const fetchTeacherSubmissions = async () => {
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions`, { headers: apiHeaders() });
      if (res.ok) setTeacherSubmissions(await res.json());
    } catch (err) { console.warn('Teacher submissions error:', err); }
  };

  // --- Туториал ---
  const startTutorial = useCallback((role) => {
    setTutorialStep(0);
    setTutorialActive(true);
  }, []);

  const handleTutorialNext = useCallback(() => {
    const steps = userRole === 'teacher' ? TEACHER_STEPS : STUDENT_STEPS;
    if (tutorialStep + 1 >= steps.length) {
      setTutorialActive(false);
      localStorage.setItem('sapsr_onboarded_' + userRole, '1');
    } else {
      setTutorialStep(s => s + 1);
    }
  }, [tutorialStep, userRole]);

  const maybeLaunchTutorial = (role) => {
    if (!localStorage.getItem('sapsr_onboarded_' + role)) {
      setTimeout(() => { setTutorialActive(true); setTutorialStep(0); }, 600);
    }
  };

  // --- Регистрация студента ---
  const handleRegisterStudent = async (e) => {
    e.preventDefault();
    setRegError('');
    const match = regInput.trim().match(/^(.+),\s*(\d{6})$/);
    if (!match) { setRegError('Формат: Иванов И.И., 123456'); return; }
    const fullName = match[1].trim() + ' (гр. ' + match[2] + ')';
    setRegistering(true);
    try {
      const res = await fetch(`${API_BASE}/register`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ role: 'STUDENT', full_name: fullName }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setUserRole('student');
        setStep('main');
        fetchTeachers();
        maybeLaunchTutorial('student');
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  // --- Регистрация преподавателя ---
  const handleTeacherSendCode = async (e) => {
    e.preventDefault();
    setRegError('');
    if (!teacherFullName.trim()) { setRegError('Введите ФИО'); return; }
    if (!teacherEmail.trim()) { setRegError('Введите email'); return; }
    setSendingCode(true);
    try {
      const res = await fetch(`${API_BASE}/register/send-code`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: teacherEmail.trim() }),
      });
      if (res.ok) { setStep('confirm_code'); setRegCode(''); }
      else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка отправки кода');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setSendingCode(false); }
  };

  const handleConfirmCode = async (e) => {
    e.preventDefault();
    setRegError('');
    if (!regCode.trim()) { setRegError('Введите код из письма'); return; }
    setRegistering(true);
    try {
      const res = await fetch(`${API_BASE}/register`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ role: 'TEACHER', full_name: teacherFullName.trim(), email: teacherEmail.trim(), code: regCode.trim() }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setUserRole('teacher');
        setStep('main');
        fetchTeacherSubmissions();
        maybeLaunchTutorial('teacher');
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  // --- Скачивание ---
  const downloadBlob = (blob, filename) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click();
    document.body.removeChild(a); URL.revokeObjectURL(url);
  };

  const handleDownloadReport = async (id) => {
    try {
      const res = await fetch(`${API_BASE}/submissions/${id}/report`, { headers: apiHeaders() });
      if (res.ok) downloadBlob(await res.blob(), `report_${id}.pdf`);
    } catch (err) { console.error(err); }
  };

  const handleDownloadPdf = async (id) => {
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions/${id}/pdf`, { headers: apiHeaders() });
      if (res.ok) downloadBlob(await res.blob(), `submission_${id}.pdf`);
    } catch (err) { console.error(err); }
  };

  // --- Вердикт ---
  const handleVerdict = async (id, verdict, comment = '') => {
    setVerdictLoading(true);
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions/${id}/verdict`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ verdict, comment }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setRevisionId(null); setRevisionComment(''); setExpandedId(null);
        fetchTeacherSubmissions();
      } else {
        const err = await res.json().catch(() => ({}));
        alert(err.error || 'Ошибка');
      }
    } catch { alert('Сервер недоступен'); }
    finally { setVerdictLoading(false); }
  };

  // --- Загрузка файла ---
  const handleSubmitFile = async (e) => {
    e.preventDefault();
    if (!file || uploading) return;
    setUploading(true); setStatus('⏳ Загрузка...');
    try {
      const formData = new FormData();
      formData.append('file', file);
      if (selectedTeacherId) formData.append('teacher_id', selectedTeacherId);
      const res = await fetch(`${API_BASE}/upload`, { method: 'POST', headers: { 'Authorization': initData }, body: formData });
      if (res.ok) {
        setStatus('✅ Работа успешно отправлена!'); setFile(null);
        tg?.HapticFeedback?.notificationOccurred('success');
        fetchSubmissions();
        tg?.showPopup({ title: 'Готово!', message: 'Файл отправлен на проверку.', buttons: [{ type: 'ok' }] });
      } else {
        const err = await res.json().catch(() => ({}));
        setStatus(`❌ Ошибка: ${err.error || res.statusText}`);
        tg?.HapticFeedback?.notificationOccurred('error');
      }
    } catch { setStatus('❌ Сервер недоступен'); }
    finally { setUploading(false); }
  };

  // --- Helpers ---
  const autoStatusLabel = (s) => {
    if (s === 'PROCESSING') return '⏳ На авто-проверке';
    if (s === 'SUCCESS')    return '✅ Оформление ОК';
    if (s === 'REJECTED')   return '❌ Ошибки оформления';
    return s;
  };

  const verdictLabel = (v) => {
    if (v === 'APPROVED') return '✅ Принято преподавателем';
    if (v === 'REVISION')  return '🔄 На доработке';
    return null;
  };

  const formatDate = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' })
      + ' ' + d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  };

  const handleTabChange = (t) => { setDirection(t > activeTab ? 1 : -1); setActiveTab(t); setStatus(''); };

  const handleRoleSelect = (role) => {
    setUserRole(role); setRegInput(''); setRegCode(''); setRegError('');
    setTeacherFullName(''); setTeacherEmail(''); setStep('register');
  };

  const resetRegistration = () => {
    setStep('role'); setUserRole(''); setRegInput(''); setRegCode('');
    setRegError(''); setTeacherFullName(''); setTeacherEmail('');
  };

  const variants = {
    enter:  (d) => ({ x: d > 0 ? 300 : -300, opacity: 0 }),
    center: { x: 0, opacity: 1 },
    exit:   (d) => ({ x: d < 0 ? 300 : -300, opacity: 0 }),
  };

  // --- Tab structure ---
  const studentTabs = [
    { icon: '📁', label: 'Загрузка' },
    { icon: '🔔', label: 'Работы', ref: refs.navNotif },
    { icon: '⚙️', label: 'Настройки', ref: refs.navSettings },
  ];
  const teacherTabs = [
    { icon: '📋', label: 'Работы' },
    { icon: '⚙️', label: 'Настройки', ref: refs.navSettings },
  ];
  const tabs = userRole === 'teacher' ? teacherTabs : studentTabs;

  return (
    <div className={`App ${theme === 'dark' ? 'app-dark' : ''} ${accessible ? 'app-a11y' : ''}`}>

      {/* Кнопка туториала */}
      {step === 'main' && (
        <button className="tutorial-btn" onClick={() => { setTutorialStep(0); setTutorialActive(true); }} title="Справка">
          💡
        </button>
      )}

      {/* Туториал */}
      {tutorialActive && (
        <TutorialOverlay
          steps={userRole === 'teacher' ? TEACHER_STEPS : STUDENT_STEPS}
          step={tutorialStep}
          onNext={handleTutorialNext}
          refs={refs}
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
          <motion.div key="loading" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Загрузка...</p>
          </motion.div>
        )}

        {step === 'role' && (
          <motion.div key="role" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Выберите вашу роль в системе</p>
            <div className="role-container">
              <button className="role-card" onClick={() => handleRoleSelect('student')}>
                <span className="role-icon">👨‍🎓</span>
                <span className="role-text">Студент</span>
              </button>
              <button className="role-card" onClick={() => handleRoleSelect('teacher')}>
                <span className="role-icon">👨‍🏫</span>
                <span className="role-text">Преподаватель</span>
              </button>
            </div>
          </motion.div>
        )}

        {step === 'register' && userRole === 'student' && (
          <motion.div key="reg-student" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Введите ФИО и номер группы</p>
            <form onSubmit={handleRegisterStudent} className="register-form">
              <input type="text" className="reg-input" placeholder="Иванов И.И., 123456"
                value={regInput} onChange={(e) => setRegInput(e.target.value)} autoFocus />
              <p className="reg-hint">Формат: ФИО, номер группы (6 цифр)</p>
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={registering}>
                  {registering ? '⏳ Регистрация...' : '✅ Зарегистрироваться'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => setStep('role')}>⬅️ Назад</button>
              </div>
            </form>
          </motion.div>
        )}

        {step === 'register' && userRole === 'teacher' && (
          <motion.div key="reg-teacher" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Регистрация преподавателя</p>
            <form onSubmit={handleTeacherSendCode} className="register-form">
              <input type="text" className="reg-input" placeholder="Иванов Иван Иванович"
                value={teacherFullName} onChange={(e) => setTeacherFullName(e.target.value)} autoFocus />
              <input type="email" className="reg-input" placeholder="ivanov@bsuir.by"
                value={teacherEmail} onChange={(e) => setTeacherEmail(e.target.value)} style={{ marginTop: 10 }} />
              <p className="reg-hint">Код подтверждения будет отправлен на указанный email</p>
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={sendingCode}>
                  {sendingCode ? '⏳ Отправка...' : '📧 Отправить код'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => setStep('role')}>⬅️ Назад</button>
              </div>
            </form>
          </motion.div>
        )}

        {step === 'confirm_code' && (
          <motion.div key="confirm-code" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Код отправлен на<br /><b>{teacherEmail}</b></p>
            <p className="reg-hint">Проверьте папку «Спам», если письмо не пришло</p>
            <form onSubmit={handleConfirmCode} className="register-form">
              <input type="text" className="reg-input code-input-wide" placeholder="6-значный код"
                value={regCode} onChange={(e) => setRegCode(e.target.value)} maxLength={6} inputMode="numeric" autoFocus />
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={registering}>
                  {registering ? '⏳ Проверка...' : '✅ Подтвердить'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => { setStep('register'); setRegError(''); setRegCode(''); }}>
                  ⬅️ Назад
                </button>
              </div>
            </form>
          </motion.div>
        )}

        {step === 'main' && (
          <motion.div key={activeTab} custom={direction} variants={variants}
            initial="enter" animate="center" exit="exit"
            transition={{ type: 'spring', stiffness: 300, damping: 30 }}
            className="screen main-content"
          >
            {/* ===== СТУДЕНТ: загрузка ===== */}
            {userRole === 'student' && activeTab === 0 && (
              <div className="tab-view">
                <h2 className="view-title">Загрузка работы</h2>
                <div className="upload-container">
                  <form onSubmit={handleSubmitFile}>
                    {teachers.length > 0 && (
                      <div className="teacher-select-wrapper" ref={refs.teacherSel}>
                        <label htmlFor="teacher-select" className="select-label">Преподаватель</label>
                        <select id="teacher-select" className="teacher-select"
                          value={selectedTeacherId} onChange={(e) => setSelectedTeacherId(e.target.value)}>
                          {teachers.map((t) => (
                            <option key={t.telegram_id || t.id} value={t.telegram_id || t.id}>
                              {t.full_name || t.name}
                            </option>
                          ))}
                        </select>
                      </div>
                    )}
                    <label htmlFor="file-upload" className="custom-file-upload" ref={refs.fileUpload}>
                      <span style={{ fontSize: '30px' }}>📁</span>
                      <span>{file ? file.name : 'Нажмите, чтобы выбрать файл (.pdf)'}</span>
                    </label>
                    <input id="file-upload" type="file" accept=".pdf"
                      onChange={(e) => { setFile(e.target.files[0]); setStatus(''); }} />
                    <button type="submit" className="submit-btn" ref={refs.submitBtn}
                      disabled={!file || uploading} style={{ marginTop: '20px' }}>
                      {uploading ? '⏳ Отправка...' : 'Отправить'}
                    </button>
                  </form>
                </div>
                {status && <div className="status-msg">{status}</div>}
              </div>
            )}

            {/* ===== СТУДЕНТ: мои работы ===== */}
            {userRole === 'student' && activeTab === 1 && (
              <div className="tab-view">
                <h2 className="view-title">Мои работы</h2>
                <button className="refresh-btn" onClick={fetchSubmissions}>🔄 Обновить</button>
                <div className="notif-window">
                  {submissions.length === 0 && <p className="notif-empty">Пока нет загруженных файлов</p>}
                  {submissions.map(s => (
                    <div key={s.id} className={`notif-line ${s.status === 'REJECTED' ? 'notif-error' : ''} ${s.status === 'SUCCESS' ? 'notif-success' : ''}`}>
                      <div className="notif-info">
                        <div className="notif-file-subject"><b>{s.file_name}</b></div>
                        <div className="notif-status">{autoStatusLabel(s.status)}</div>
                        {s.teacher_verdict && (
                          <div className={`notif-verdict ${s.teacher_verdict === 'APPROVED' ? 'verdict-ok' : 'verdict-revision'}`}>
                            {verdictLabel(s.teacher_verdict)}
                          </div>
                        )}
                        {s.teacher_comment && <div className="notif-comment">💬 {s.teacher_comment}</div>}
                      </div>
                      {s.status !== 'PROCESSING' && (
                        <button className="download-btn" onClick={() => handleDownloadReport(s.id)}>📥</button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* ===== ПРЕПОДАВАТЕЛЬ: дашборд ===== */}
            {userRole === 'teacher' && activeTab === 0 && (
              <div className="tab-view">
                <h2 className="view-title">Работы студентов</h2>
                <button className="refresh-btn" onClick={fetchTeacherSubmissions}>🔄 Обновить</button>
                <div className="notif-window" ref={refs.submissions}>
                  {teacherSubmissions.length === 0 && <p className="notif-empty">Нет работ, ожидающих проверки</p>}
                  {teacherSubmissions.map(s => {
                    const isExpanded = expandedId === s.id;
                    return (
                      <div key={s.id} className={`ts-card-compact ${isExpanded ? 'ts-card-expanded' : ''}`}>
                        <div className="ts-card-main" onClick={() => setExpandedId(isExpanded ? null : s.id)}>
                          <div className="ts-row-top">
                            <span className="ts-student">{s.student_name || 'Студент'}</span>
                            <span className="ts-date">{formatDate(s.created_at)}</span>
                          </div>
                          <div className="ts-row-bottom">
                            <span className="ts-file">{s.file_name}</span>
                            <span className="ts-chevron">{isExpanded ? '▲' : '▼'}</span>
                          </div>
                        </div>
                        {isExpanded && (
                          <div className="ts-actions">
                            <button className="download-btn-sm" onClick={() => handleDownloadPdf(s.id)}>📄 Скачать PDF</button>
                            <button className="approve-btn" disabled={verdictLoading}
                              onClick={() => handleVerdict(s.id, 'APPROVED')}>
                              ✅ Принять
                            </button>
                            {revisionId === s.id ? (
                              <div className="revision-form">
                                <textarea className="revision-input" rows={3}
                                  placeholder="Комментарий для студента..."
                                  value={revisionComment} onChange={(e) => setRevisionComment(e.target.value)} />
                                <div className="revision-btns">
                                  <button className="submit-btn" disabled={!revisionComment.trim() || verdictLoading}
                                    onClick={() => handleVerdict(s.id, 'REVISION', revisionComment)}>
                                    {verdictLoading ? '⏳' : 'Отправить'}
                                  </button>
                                  <button className="secondary-btn" onClick={() => { setRevisionId(null); setRevisionComment(''); }}>
                                    Отмена
                                  </button>
                                </div>
                              </div>
                            ) : (
                              <button className="revision-btn" onClick={() => { setRevisionId(s.id); setRevisionComment(''); }}>
                                🔄 На доработку
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* ===== НАСТРОЙКИ (общие) ===== */}
            {activeTab === (userRole === 'teacher' ? 1 : 2) && (
              <div className="tab-view">
                <h2 className="view-title">Настройки</h2>

                <div className="settings-section">
                  <div className="settings-label">Тема оформления</div>
                  <div className="theme-toggle">
                    <button className={`theme-btn ${theme === 'light' ? 'active' : ''}`} onClick={() => setTheme('light')}>
                      ☀️ Светлая
                    </button>
                    <button className={`theme-btn ${theme === 'dark' ? 'active' : ''}`} onClick={() => setTheme('dark')}>
                      🌙 Тёмная
                    </button>
                  </div>
                </div>

                <div className="settings-section">
                  <div className="settings-label">Режим для слабовидящих</div>
                  <div className="settings-row">
                    <span className="settings-desc">Увеличенный текст, высокий контраст</span>
                    <button className={`toggle-switch ${accessible ? 'on' : ''}`}
                      onClick={() => setAccessible(a => !a)}>
                      <span className="toggle-thumb" />
                    </button>
                  </div>
                </div>

                <div className="settings-divider" />
                <button className="secondary-btn" onClick={resetRegistration}>Выйти из системы</button>
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      {step === 'main' && (
        <div className="nav-wrapper">
          <div className="bottom-nav">
            {tabs.map((tab, i) => (
              <button key={i} ref={tab.ref || null}
                className={activeTab === i ? 'active' : ''}
                onClick={() => handleTabChange(i)}>
                <div className="nav-icon-bg">{tab.icon}</div>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
