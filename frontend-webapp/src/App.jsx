import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import './App.css';

const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1';

function App() {
  const [step, setStep] = useState('loading');
  const [activeTab, setActiveTab] = useState(1);
  const [direction, setDirection] = useState(0);
  const [userRole, setUserRole] = useState('');

  // Регистрация студента
  const [regInput, setRegInput] = useState('');
  const [regError, setRegError] = useState('');
  const [registering, setRegistering] = useState(false);

  // Регистрация преподавателя
  const [teacherFullName, setTeacherFullName] = useState('');
  const [teacherEmail, setTeacherEmail] = useState('');
  const [regCode, setRegCode] = useState('');
  const [sendingCode, setSendingCode] = useState(false);

  // Загрузка файла (студент)
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState('');
  const [teachers, setTeachers] = useState([]);
  const [selectedTeacherId, setSelectedTeacherId] = useState('');
  const [uploading, setUploading] = useState(false);
  const [submissions, setSubmissions] = useState([]);

  // Дашборд преподавателя
  const [teacherSubmissions, setTeacherSubmissions] = useState([]);
  const [revisionId, setRevisionId] = useState(null);
  const [revisionComment, setRevisionComment] = useState('');
  const [verdictLoading, setVerdictLoading] = useState(false);

  const tg = window.Telegram?.WebApp;
  const initData = tg?.initData || '';
  const apiHeaders = (extra = {}) => ({ 'Authorization': initData, ...extra });

  useEffect(() => {
    tg?.ready();
    tg?.expand();
    checkAuth();
  }, []);

  const checkAuth = async () => {
    try {
      const res = await fetch(`${API_BASE}/me`, { headers: apiHeaders() });
      if (res.ok) {
        const data = await res.json();
        if (data.role && data.role !== 'NONE') {
          const role = data.role.toLowerCase();
          setUserRole(role);
          setStep('main');
          if (role === 'student') {
            fetchTeachers();
            fetchSubmissions();
            setActiveTab(0);
          } else {
            fetchTeacherSubmissions();
            setActiveTab(0);
          }
          return;
        }
      }
    } catch (err) {
      console.warn('Не удалось проверить авторизацию:', err);
    }
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
    } catch (err) {
      console.warn('Не удалось загрузить преподавателей:', err);
    }
  };

  const fetchSubmissions = async () => {
    try {
      const res = await fetch(`${API_BASE}/submissions`, { headers: apiHeaders() });
      if (res.ok) setSubmissions(await res.json());
    } catch (err) {
      console.warn('Ошибка загрузки истории:', err);
    }
  };

  const fetchTeacherSubmissions = async () => {
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions`, { headers: apiHeaders() });
      if (res.ok) setTeacherSubmissions(await res.json());
    } catch (err) {
      console.warn('Ошибка загрузки работ:', err);
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
        setActiveTab(0);
        fetchTeachers();
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  // --- Регистрация преподавателя: шаг 1 — отправить код ---
  const handleTeacherSendCode = async (e) => {
    e.preventDefault();
    setRegError('');
    if (!teacherFullName.trim()) { setRegError('Введите ФИО'); return; }
    if (!teacherEmail.trim().toLowerCase().includes('@bsuir.by')) {
      setRegError('Введите корпоративную почту @bsuir.by');
      return;
    }
    setSendingCode(true);
    try {
      const res = await fetch(`${API_BASE}/register/send-code`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: teacherEmail.trim() }),
      });
      if (res.ok) {
        setStep('confirm_code');
        setRegCode('');
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка отправки кода');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setSendingCode(false); }
  };

  // --- Регистрация преподавателя: шаг 2 — подтвердить код ---
  const handleConfirmCode = async (e) => {
    e.preventDefault();
    setRegError('');
    if (!regCode.trim()) { setRegError('Введите код из письма'); return; }
    setRegistering(true);
    try {
      const res = await fetch(`${API_BASE}/register`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({
          role: 'TEACHER',
          full_name: teacherFullName.trim(),
          email: teacherEmail.trim(),
          code: regCode.trim(),
        }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setUserRole('teacher');
        setStep('main');
        setActiveTab(0);
        fetchTeacherSubmissions();
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  // --- Скачивание отчёта студента ---
  const handleDownloadReport = async (submissionId) => {
    try {
      const res = await fetch(`${API_BASE}/submissions/${submissionId}/report`, { headers: apiHeaders() });
      if (!res.ok) return;
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = `report_${submissionId}.txt`;
      document.body.appendChild(link); link.click();
      document.body.removeChild(link); URL.revokeObjectURL(url);
    } catch (err) { console.error('Ошибка скачивания отчёта:', err); }
  };

  // --- Скачивание PDF преподавателем ---
  const handleDownloadPdf = async (submissionId) => {
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions/${submissionId}/pdf`, { headers: apiHeaders() });
      if (!res.ok) return;
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = `submission_${submissionId}.pdf`;
      document.body.appendChild(link); link.click();
      document.body.removeChild(link); URL.revokeObjectURL(url);
    } catch (err) { console.error('Ошибка скачивания PDF:', err); }
  };

  // --- Вердикт преподавателя ---
  const handleVerdict = async (submissionId, verdict, comment = '') => {
    setVerdictLoading(true);
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions/${submissionId}/verdict`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ verdict, comment }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setRevisionId(null);
        setRevisionComment('');
        fetchTeacherSubmissions();
      } else {
        const err = await res.json().catch(() => ({}));
        alert(err.error || 'Ошибка вынесения вердикта');
      }
    } catch { alert('Сервер недоступен'); }
    finally { setVerdictLoading(false); }
  };

  // --- Загрузка файла студентом ---
  const handleSubmitFile = async (e) => {
    e.preventDefault();
    if (!file || uploading) return;
    setUploading(true);
    setStatus('⏳ Загрузка...');
    try {
      const formData = new FormData();
      formData.append('file', file);
      if (selectedTeacherId) formData.append('teacher_id', selectedTeacherId);
      const res = await fetch(`${API_BASE}/upload`, {
        method: 'POST',
        headers: { 'Authorization': initData },
        body: formData,
      });
      if (res.ok) {
        setStatus('✅ Работа успешно отправлена!');
        setFile(null);
        tg?.HapticFeedback?.notificationOccurred('success');
        fetchSubmissions();
        tg?.showPopup({ title: 'Готово!', message: 'Файл отправлен на проверку. Результат появится в уведомлениях.', buttons: [{ type: 'ok' }] });
      } else {
        const err = await res.json().catch(() => ({}));
        setStatus(`❌ Ошибка: ${err.error || res.statusText}`);
        tg?.HapticFeedback?.notificationOccurred('error');
      }
    } catch (err) {
      setStatus('❌ Сервер недоступен');
      console.error(err);
    } finally { setUploading(false); }
  };

  const autoStatusLabel = (s) => {
    switch (s) {
      case 'PROCESSING': return '⏳ На авто-проверке';
      case 'SUCCESS':    return '✅ Проверка пройдена';
      case 'REJECTED':   return '❌ Ошибки оформления';
      default:           return s;
    }
  };

  const verdictLabel = (v) => {
    if (v === 'APPROVED') return '✅ Принято преподавателем';
    if (v === 'REVISION') return '🔄 На доработке';
    return null;
  };

  const scoreColor = (score) => {
    if (score == null) return '';
    if (score >= 80) return '#4caf50';
    if (score >= 60) return '#ff9800';
    return '#f44336';
  };

  const handleTabChange = (newTab) => {
    setDirection(newTab > activeTab ? 1 : -1);
    setActiveTab(newTab);
    setStatus('');
  };

  const handleRoleSelect = (role) => {
    setUserRole(role);
    setRegInput(''); setRegCode(''); setRegError('');
    setTeacherFullName(''); setTeacherEmail('');
    setStep('register');
  };

  const resetRegistration = () => {
    setStep('role'); setRegInput(''); setRegCode('');
    setRegError(''); setTeacherFullName(''); setTeacherEmail('');
  };

  const variants = {
    enter: (d) => ({ x: d > 0 ? 300 : -300, opacity: 0 }),
    center: { x: 0, opacity: 1 },
    exit: (d) => ({ x: d < 0 ? 300 : -300, opacity: 0 }),
  };

  return (
    <div className="App">
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
              <input
                type="text" className="reg-input" placeholder="Иванов И.И., 123456"
                value={regInput} onChange={(e) => setRegInput(e.target.value)} autoFocus
              />
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
              <input
                type="text" className="reg-input" placeholder="Иванов Иван Иванович"
                value={teacherFullName} onChange={(e) => setTeacherFullName(e.target.value)}
                autoFocus
              />
              <input
                type="email" className="reg-input" placeholder="ivanov@bsuir.by"
                value={teacherEmail} onChange={(e) => setTeacherEmail(e.target.value)}
                style={{ marginTop: 10 }}
              />
              <p className="reg-hint">Требуется корпоративная почта @bsuir.by</p>
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
            <p className="description">
              Код подтверждения отправлен на<br /><b>{teacherEmail}</b>
            </p>
            <p className="reg-hint">Проверьте папку «Спам», если письмо не пришло</p>
            <form onSubmit={handleConfirmCode} className="register-form">
              <input
                type="text" className="reg-input code-input-wide"
                placeholder="Введите 6-значный код"
                value={regCode} onChange={(e) => setRegCode(e.target.value)}
                maxLength={6} inputMode="numeric" autoFocus
              />
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
          <motion.div
            key={activeTab} custom={direction} variants={variants}
            initial="enter" animate="center" exit="exit"
            transition={{ type: 'spring', stiffness: 300, damping: 30 }}
            className="screen main-content"
          >
            {/* ===== СТУДЕНТ: вкладка загрузки ===== */}
            {userRole === 'student' && activeTab === 0 && (
              <div className="tab-view">
                <h2 className="view-title">Загрузка работы</h2>
                <div className="upload-container">
                  <form onSubmit={handleSubmitFile}>
                    {teachers.length > 0 && (
                      <div className="teacher-select-wrapper">
                        <label htmlFor="teacher-select" className="select-label">Преподаватель</label>
                        <select
                          id="teacher-select" className="teacher-select"
                          value={selectedTeacherId}
                          onChange={(e) => setSelectedTeacherId(e.target.value)}
                        >
                          {teachers.map((t) => (
                            <option key={t.telegram_id || t.id} value={t.telegram_id || t.id}>
                              {t.full_name || t.fio || t.name}
                            </option>
                          ))}
                        </select>
                      </div>
                    )}
                    <label htmlFor="file-upload" className="custom-file-upload">
                      <span style={{ fontSize: '30px' }}>📁</span>
                      <span>{file ? file.name : 'Нажмите, чтобы выбрать файл (.pdf)'}</span>
                    </label>
                    <input id="file-upload" type="file" accept=".pdf"
                      onChange={(e) => { setFile(e.target.files[0]); setStatus(''); }} />
                    <button type="submit" className="submit-btn" disabled={!file || uploading} style={{ marginTop: '20px' }}>
                      {uploading ? '⏳ Отправка...' : 'Отправить'}
                    </button>
                  </form>
                </div>
                {status && <div className="status-msg">{status}</div>}
              </div>
            )}

            {/* ===== СТУДЕНТ: вкладка уведомлений ===== */}
            {userRole === 'student' && activeTab === 1 && (
              <div className="tab-view">
                <h2 className="view-title">Мои работы</h2>
                <button className="refresh-btn" onClick={fetchSubmissions}>🔄 Обновить</button>
                <div className="notif-window">
                  {submissions.length === 0 && (
                    <p className="notif-empty">Пока нет загруженных файлов</p>
                  )}
                  {submissions.map(s => (
                    <div key={s.id} className={`notif-line ${s.status === 'REJECTED' ? 'notif-error' : ''} ${s.status === 'SUCCESS' ? 'notif-success' : ''}`}>
                      <div className="notif-info">
                        <div className="notif-file-subject"><b>{s.file_name}</b></div>
                        <div className="notif-status">{autoStatusLabel(s.status)}</div>
                        {s.score != null && s.status !== 'PROCESSING' && (
                          <div className="notif-score" style={{ color: scoreColor(s.score) }}>
                            Оценка оформления: {s.score}/100
                          </div>
                        )}
                        {s.teacher_verdict && (
                          <div className={`notif-verdict ${s.teacher_verdict === 'APPROVED' ? 'verdict-ok' : 'verdict-revision'}`}>
                            {verdictLabel(s.teacher_verdict)}
                          </div>
                        )}
                        {s.teacher_comment && (
                          <div className="notif-comment">💬 {s.teacher_comment}</div>
                        )}
                      </div>
                      {s.status !== 'PROCESSING' && (
                        <button className="download-btn" onClick={() => handleDownloadReport(s.id)}>📥</button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* ===== ПРЕПОДАВАТЕЛЬ: дашборд работ ===== */}
            {userRole === 'teacher' && activeTab === 0 && (
              <div className="tab-view">
                <h2 className="view-title">Работы студентов</h2>
                <button className="refresh-btn" onClick={fetchTeacherSubmissions}>🔄 Обновить</button>
                <div className="notif-window">
                  {teacherSubmissions.length === 0 && (
                    <p className="notif-empty">Нет работ, ожидающих проверки</p>
                  )}
                  {teacherSubmissions.map(s => (
                    <div key={s.id} className="teacher-submission-card">
                      <div className="ts-header">
                        <span className="ts-student">{s.student_name || 'Студент'}</span>
                        <span className="ts-date">{s.created_at ? s.created_at.substring(0, 10) : ''}</span>
                      </div>
                      <div className="ts-file">{s.file_name}</div>
                      {s.score != null && (
                        <div className="ts-score" style={{ color: scoreColor(s.score) }}>
                          Оценка оформления: {s.score}/100
                        </div>
                      )}
                      {s.teacher_verdict ? (
                        <div className={`ts-verdict ${s.teacher_verdict === 'APPROVED' ? 'verdict-ok' : 'verdict-revision'}`}>
                          {verdictLabel(s.teacher_verdict)}
                          {s.teacher_comment && <div className="ts-comment">💬 {s.teacher_comment}</div>}
                        </div>
                      ) : (
                        <div className="ts-actions">
                          <button className="download-btn-sm" onClick={() => handleDownloadPdf(s.id)}>
                            📄 Скачать PDF
                          </button>
                          <button
                            className="approve-btn"
                            disabled={verdictLoading}
                            onClick={() => handleVerdict(s.id, 'APPROVED')}
                          >
                            ✅ Принять
                          </button>
                          {revisionId === s.id ? (
                            <div className="revision-form">
                              <textarea
                                className="revision-input"
                                placeholder="Комментарий для студента..."
                                value={revisionComment}
                                onChange={(e) => setRevisionComment(e.target.value)}
                                rows={3}
                              />
                              <div className="revision-btns">
                                <button
                                  className="submit-btn"
                                  disabled={!revisionComment.trim() || verdictLoading}
                                  onClick={() => handleVerdict(s.id, 'REVISION', revisionComment)}
                                >
                                  {verdictLoading ? '⏳' : 'Отправить'}
                                </button>
                                <button
                                  className="secondary-btn"
                                  onClick={() => { setRevisionId(null); setRevisionComment(''); }}
                                >
                                  Отмена
                                </button>
                              </div>
                            </div>
                          ) : (
                            <button
                              className="revision-btn"
                              onClick={() => { setRevisionId(s.id); setRevisionComment(''); }}
                            >
                              🔄 На доработку
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* ===== ВЫХОД ===== */}
            {activeTab === 2 && (
              <div className="tab-view">
                <h2 className="view-title">Выход</h2>
                <p className="description">Выйти из системы?</p>
                <div className="vertical-button-group">
                  <button className="submit-btn" onClick={resetRegistration}>Да, выйти</button>
                  <button className="secondary-btn" onClick={() => setActiveTab(0)}>Отмена</button>
                </div>
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>

      {step === 'main' && (
        <div className="nav-wrapper">
          <div className="bottom-nav">
            <button className={activeTab === 0 ? 'active' : ''} onClick={() => handleTabChange(0)}>
              <div className="nav-icon-bg">{userRole === 'teacher' ? '📋' : '📁'}</div>
            </button>
            {userRole === 'student' && (
              <button className={activeTab === 1 ? 'active' : ''} onClick={() => handleTabChange(1)}>
                <div className="nav-icon-bg">🔔</div>
              </button>
            )}
            <button className={activeTab === 2 ? 'active' : ''} onClick={() => handleTabChange(2)}>
              <div className="nav-icon-bg">🚪</div>
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
