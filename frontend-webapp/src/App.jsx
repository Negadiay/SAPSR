import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import './App.css';

const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1';

function App() {
  const [step, setStep] = useState('loading');
  const [activeTab, setActiveTab] = useState(1);
  const [direction, setDirection] = useState(0);
  const [userRole, setUserRole] = useState('');
  const [regError, setRegError] = useState('');
  const [registering, setRegistering] = useState(false);
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState('');
  const [teachers, setTeachers] = useState([]);
  const [selectedTeacherId, setSelectedTeacherId] = useState('');
  const [uploading, setUploading] = useState(false);
  const [submissions, setSubmissions] = useState([]);

  // Student registration fields
  const [studentFio, setStudentFio] = useState('');
  const [studentGroup, setStudentGroup] = useState('');

  // Teacher registration fields
  const [teacherEmail, setTeacherEmail] = useState('');
  const [teacherName, setTeacherName] = useState('');
  const [verifyCode, setVerifyCode] = useState('');
  const [devCode, setDevCode] = useState('');

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
          setUserRole(data.role.toLowerCase());
          setStep('main');
          fetchTeachers();
          fetchSubmissions();
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
      }
    } catch (err) {
      console.warn('Не удалось загрузить преподавателей:', err);
    }
  };

  const fetchSubmissions = async () => {
    try {
      const res = await fetch(`${API_BASE}/submissions`, { headers: apiHeaders() });
      if (res.ok) {
        const data = await res.json();
        setSubmissions(data);
      }
    } catch (err) {
      console.warn('Не удалось загрузить историю:', err);
    }
  };

  const handleRegisterStudent = async (e) => {
    e.preventDefault();
    setRegError('');

    const fio = studentFio.trim();
    const group = studentGroup.trim();

    const fioPattern = /^[А-ЯЁ][а-яё]+(-[А-ЯЁ][а-яё]+)?\s+[А-ЯЁ]\.[А-ЯЁ]\.$/;
    if (!fioPattern.test(fio)) {
      setRegError('ФИО должно быть в формате: Фамилия И.О. (например, Иванов И.И.)');
      return;
    }

    if (!/^\d{6}$/.test(group)) {
      setRegError('Номер группы — 6 цифр');
      return;
    }

    setRegistering(true);
    try {
      const res = await fetch(`${API_BASE}/register/student`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ full_name: fio, group_number: group }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setUserRole('student');
        setStep('main');
        fetchTeachers();
        fetchSubmissions();
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch {
      setRegError('Сервер недоступен');
    } finally {
      setRegistering(false);
    }
  };

  const handleTeacherEmailSubmit = async (e) => {
    e.preventDefault();
    setRegError('');

    const email = teacherEmail.trim().toLowerCase();
    if (!email.endsWith('@bsuir.by')) {
      setRegError('Введите почту @bsuir.by');
      return;
    }

    setRegistering(true);
    try {
      const res = await fetch(`${API_BASE}/auth/send-code`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ email }),
      });
      const data = await res.json();
      if (res.ok) {
        setTeacherName(data.teacher_name || '');
        if (data.dev_code) setDevCode(data.dev_code);
        setStep('confirm_code');
      } else {
        setRegError(data.error || 'Ошибка отправки кода');
      }
    } catch {
      setRegError('Сервер недоступен');
    } finally {
      setRegistering(false);
    }
  };

  const handleConfirmCode = async (e) => {
    e.preventDefault();
    setRegError('');

    setRegistering(true);
    try {
      const res = await fetch(`${API_BASE}/auth/verify-code`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ email: teacherEmail.trim().toLowerCase(), code: verifyCode.trim() }),
      });
      const data = await res.json();
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setUserRole('teacher');
        setStep('main');
        fetchTeachers();
        fetchSubmissions();
      } else {
        setRegError(data.error || 'Ошибка подтверждения');
      }
    } catch {
      setRegError('Сервер недоступен');
    } finally {
      setRegistering(false);
    }
  };

  const handleDownloadReport = async (submissionId) => {
    try {
      const res = await fetch(`${API_BASE}/submissions/${submissionId}/report`, {
        headers: apiHeaders(),
      });
      if (!res.ok) return;

      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `report_${submissionId}.pdf`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);

      tg?.HapticFeedback?.notificationOccurred('success');
    } catch (err) {
      console.error('Ошибка скачивания отчёта:', err);
    }
  };

  const statusLabel = (s) => {
    switch (s) {
      case 'PROCESSING': return '⏳ На проверке';
      case 'SUCCESS': return '✅ Принято';
      case 'REJECTED': return '❌ Отклонено';
      case 'FAIL': return '❌ Ошибки';
      default: return s;
    }
  };

  const handleTabChange = (newTab) => {
    setDirection(newTab > activeTab ? 1 : -1);
    setActiveTab(newTab);
    setStatus('');
  };

  const handleRoleSelect = (role) => {
    setUserRole(role);
    setStudentFio('');
    setStudentGroup('');
    setTeacherEmail('');
    setTeacherName('');
    setVerifyCode('');
    setDevCode('');
    setRegError('');
    setStep('register');
  };

  const handleSubmitFile = async (e) => {
    e.preventDefault();
    if (!file || uploading) return;

    if (!selectedTeacherId) {
      setStatus('❌ Выберите преподавателя');
      return;
    }

    setUploading(true);
    setStatus('⏳ Загрузка...');

    try {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('teacher_id', selectedTeacherId);

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
        tg?.showPopup(
          { title: 'Готово!', message: 'Файл отправлен на проверку. Результат появится в уведомлениях.', buttons: [{ type: 'ok' }] },
        );
      } else {
        const err = await res.json().catch(() => ({}));
        setStatus(`❌ Ошибка: ${err.error || res.statusText}`);
        tg?.HapticFeedback?.notificationOccurred('error');
      }
    } catch (err) {
      setStatus('❌ Сервер недоступен');
      console.error(err);
    } finally {
      setUploading(false);
    }
  };

  const variants = {
    enter: (direction) => ({ x: direction > 0 ? 300 : -300, opacity: 0 }),
    center: { x: 0, opacity: 1 },
    exit: (direction) => ({ x: direction < 0 ? 300 : -300, opacity: 0 })
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
          <motion.div key="loading" className="screen" exit={{opacity: 0}}>
            <p className="description">Загрузка...</p>
          </motion.div>
        )}

        {step === 'role' && (
          <motion.div key="role" className="screen" exit={{opacity: 0}}>
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
          <motion.div key="reg-student" className="screen" exit={{opacity: 0}}>
            <p className="description">Введите ФИО и номер группы</p>
            <form onSubmit={handleRegisterStudent} className="register-form">
              <input
                type="text"
                className="reg-input"
                placeholder="Иванов И.И."
                value={studentFio}
                onChange={(e) => setStudentFio(e.target.value)}
                autoFocus
              />
              <input
                type="text"
                className="reg-input"
                placeholder="123456"
                value={studentGroup}
                onChange={(e) => setStudentGroup(e.target.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                maxLength={6}
              />
              <p className="reg-hint">ФИО: Фамилия И.О. | Группа: 6 цифр</p>
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
          <motion.div key="reg-teacher" className="screen" exit={{opacity: 0}}>
            <p className="description">Введите вашу рабочую почту @bsuir.by</p>
            <form onSubmit={handleTeacherEmailSubmit} className="register-form">
              <input
                type="email"
                className="reg-input"
                placeholder="ivanov@bsuir.by"
                value={teacherEmail}
                onChange={(e) => setTeacherEmail(e.target.value)}
                autoFocus
              />
              <p className="reg-hint">Почта должна быть зарегистрирована в системе</p>
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={registering}>
                  {registering ? '⏳ Проверка...' : 'Отправить код'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => setStep('role')}>⬅️ Назад</button>
              </div>
            </form>
          </motion.div>
        )}

        {step === 'confirm_code' && (
          <motion.div key="confirm-code" className="screen" exit={{opacity: 0}}>
            {devCode && (
              <div className="debug-banner">
                [DEV] Код подтверждения: {devCode}
              </div>
            )}
            {teacherName && <p className="description" style={{fontWeight: 'bold'}}>{teacherName}</p>}
            <p className="description">На почту {teacherEmail} отправлен код подтверждения</p>
            <form onSubmit={handleConfirmCode} className="register-form">
              <input
                type="text"
                className="reg-input code-input-wide"
                placeholder="Введите 6-значный код"
                value={verifyCode}
                onChange={(e) => setVerifyCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                maxLength={6}
                inputMode="numeric"
                autoFocus
              />
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={registering}>
                  {registering ? '⏳ Проверка...' : '✅ Подтвердить'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => { setStep('register'); setRegError(''); setVerifyCode(''); setDevCode(''); }}>⬅️ Назад</button>
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
            {activeTab === 1 && (
              <div className="tab-view">
                <h2 className="view-title">Уведомления</h2>
                <button className="refresh-btn" onClick={fetchSubmissions}>🔄 Обновить</button>
                <div className="notif-window">
                  {submissions.length === 0 && (
                    <p className="notif-empty">Пока нет загруженных файлов</p>
                  )}
                  {submissions.map(s => (
                    <div key={s.id} className={`notif-line ${s.status === 'REJECTED' || s.status === 'FAIL' ? 'notif-error' : ''} ${s.status === 'SUCCESS' ? 'notif-success' : ''}`}>
                      <div className="notif-info">
                        <div className="notif-file-subject">
                          <b>{s.file_name}</b>
                          {s.teacher_name && <span className="notif-teacher"> → {s.teacher_name}</span>}
                        </div>
                        <div className="notif-status">
                          {statusLabel(s.status)}
                        </div>
                        {s.format_errors && s.format_errors !== '[]' && s.format_errors !== 'null' && (
                          <div className="notif-errors">Есть ошибки форматирования</div>
                        )}
                      </div>
                      {s.status !== 'PROCESSING' && (
                        <button className="download-btn" onClick={() => handleDownloadReport(s.id)}>
                          📥
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {activeTab === 0 && (
              <div className="tab-view">
                <h2 className="view-title">Загрузка</h2>
                <div className="upload-container">
                  <form onSubmit={handleSubmitFile}>
                    <div className="teacher-select-wrapper">
                      <label htmlFor="teacher-select" className="select-label">Преподаватель</label>
                      {teachers.length > 0 ? (
                        <select
                          id="teacher-select"
                          className="teacher-select"
                          value={selectedTeacherId}
                          onChange={(e) => setSelectedTeacherId(e.target.value)}
                        >
                          <option value="">— Выберите преподавателя —</option>
                          {teachers.map((t) => (
                            <option key={t.id} value={t.id}>
                              {t.full_name}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <p className="notif-empty">Нет доступных преподавателей</p>
                      )}
                    </div>
                    <label htmlFor="file-upload" className="custom-file-upload">
                      <span style={{fontSize: '30px'}}>📁</span>
                      <span>{file ? file.name : 'Нажмите, чтобы выбрать файл (.pdf)'}</span>
                    </label>
                    <input id="file-upload" type="file" accept=".pdf" onChange={(e) => {setFile(e.target.files[0]); setStatus('')}} />
                    <button type="submit" className="submit-btn" disabled={!file || !selectedTeacherId || uploading} style={{marginTop: '20px'}}>
                      {uploading ? '⏳ Отправка...' : 'Отправить'}
                    </button>
                  </form>
                </div>
                {status && <div className="status-msg">{status}</div>}
              </div>
            )}

            {activeTab === 2 && (
              <div className="tab-view">
                <h2 className="view-title">Выход</h2>
                <p className="description">Выйти из системы?</p>
                <div className="vertical-button-group">
                   <button className="submit-btn" onClick={() => {setStep('role'); setActiveTab(1);}}>Да, выйти</button>
                   <button className="secondary-btn" onClick={() => setActiveTab(1)}>Отмена</button>
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
                <div className="nav-icon-bg">📁</div>
              </button>
              <button className={activeTab === 1 ? 'active' : ''} onClick={() => handleTabChange(1)}>
                <div className="nav-icon-bg">🔔</div>
              </button>
              <button className={activeTab === 2 ? 'active' : ''} onClick={() => handleTabChange(2)}>
                <div className="nav-icon-bg">🚪</div>
              </button>
            </div>
            <button className="back-link-bottom" onClick={() => setStep('role')}>Сменить роль</button>
        </div>
      )}
    </div>
  );
}

export default App;
