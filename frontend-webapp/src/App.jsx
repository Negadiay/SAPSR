import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import './App.css';

function App() {
  const [step, setStep] = useState('role');
  const [activeTab, setActiveTab] = useState(1);
  const [direction, setDirection] = useState(0);
  const [userRole, setUserRole] = useState('');
  const [accessCode, setAccessCode] = useState('');
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState('');

  // Теперь только .pdf файлы
  const notifications = [
    { id: 1, subject: 'ИАД', teacher: 'Пакутник Д.В.', fileName: 'Задание_1.pdf' },
    { id: 2, subject: 'ГИИС', teacher: 'Сальников Д.А.', fileName: 'Отчет_проверки.pdf' },
    { id: 3, subject: 'ОС', teacher: 'Иванов И.И.', fileName: 'Результат_лаб.pdf' },
  ];

  useEffect(() => {
    window.Telegram.WebApp.ready();
    window.Telegram.WebApp.expand();
  }, []);

  // Функция скачивания (имитация PDF)
  const handleDownload = (fileName) => {
    // Создаем минимальный набор байтов, который браузер примет за PDF
    const dummyPdfContent = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/MediaBox[0 0 612 792]>>endobj\nxref\n0 4\n0000000000 65535 f\ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n190\n%%EOF";

    const blob = new Blob([dummyPdfContent], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName; // Имя уже содержит .pdf
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);

    window.Telegram.WebApp.HapticFeedback.notificationOccurred('success');
  };

  const handleTabChange = (newTab) => {
    setDirection(newTab > activeTab ? 1 : -1);
    setActiveTab(newTab);
    setStatus('');
  };

  const handleRoleSelect = (role) => {
    setUserRole(role);
    setStep('code');
  };

  const handleCodeSubmit = (e) => {
    e.preventDefault();
    if (accessCode.length > 0) setStep('main');
  };

  const handleSubmitFile = async (e) => {
    e.preventDefault();
    if (!file) return;
    setStatus('⏳ Загрузка...');
    setTimeout(() => {
      setStatus('✅ Работа успешно отправлена!');
      window.Telegram.WebApp.HapticFeedback.notificationOccurred('success');
    }, 1500);
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
            <button className="skip-btn" onClick={() => setStep('main')}>Пропустить регистрацию ➔</button>
          </motion.div>
        )}

        {step === 'code' && (
          <motion.div key="code" className="screen" exit={{opacity: 0}}>
            <p className="description">Введите код доступа для роли <br/> <b>{userRole === 'student' ? 'Студент' : 'Преподаватель'}</b></p>
            <form onSubmit={handleCodeSubmit} className="code-form-container">
              <input type="text" className="code-input" placeholder="Код..." value={accessCode} onChange={(e) => setAccessCode(e.target.value)} autoFocus />
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn">✅ Войти</button>
                <button type="button" className="secondary-btn" onClick={() => setStep('role')}>⬅️ Назад</button>
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
                <div className="notif-window">
                   {notifications.map(n => (
                     <div key={n.id} className="notif-line">
                       <div className="notif-info">
                         <div className="notif-file-subject">
                           <b>{n.fileName}</b>: {n.subject}
                         </div>
                         <div className="notif-teacher">
                           {n.teacher}
                         </div>
                       </div>
                       <button className="download-btn" onClick={() => handleDownload(n.fileName)}>
                        📥
                       </button>
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
                    <label htmlFor="file-upload" className="custom-file-upload">
                      <span style={{fontSize: '30px'}}>📁</span>
                      <span>{file ? file.name : 'Нажмите, чтобы выбрать файл'}</span>
                    </label>
                    <input id="file-upload" type="file" onChange={(e) => {setFile(e.target.files[0]); setStatus('')}} />
                    <button type="submit" className="submit-btn" disabled={!file} style={{marginTop: '20px'}}>Отправить</button>
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