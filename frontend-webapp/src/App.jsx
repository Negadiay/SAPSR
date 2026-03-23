import { useState, useEffect } from 'react';
import './App.css';

function App() {
  // Навигация: 'role' (выбор), 'code' (ввод кода), 'main' (загрузка)
  const [step, setStep] = useState('role');
  const [userRole, setUserRole] = useState('');
  const [accessCode, setAccessCode] = useState('');

  // Загрузка файла
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState('');

  useEffect(() => {
    window.Telegram.WebApp.ready();
    window.Telegram.WebApp.expand();
  }, []);

  const handleRoleSelect = (role) => {
    setUserRole(role);
    setStep('code');
  };

  const handleCodeSubmit = (e) => {
    e.preventDefault();
    // Пока пускаем с любым кодом
    if (accessCode.length > 0) {
      setStep('main');
    }
  };

  const skipRegistration = () => {
    setUserRole('guest');
    setStep('main');
  };

  const handleFileChange = (e) => {
    if (e.target.files[0]) {
      setFile(e.target.files[0]);
      setStatus('');
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!file) return;

    const formData = new FormData();
    formData.append('file', file);
    formData.append('role', userRole); // Добавляем роль в запрос

    const userId = window.Telegram.WebApp.initDataUnsafe?.user?.id || 'unknown';
    formData.append('userId', userId);

    try {
      setStatus('⏳ Загрузка...');
      const response = await fetch('http://localhost:8080/upload', {
        method: 'POST',
        body: formData,
      });

      if (response.ok) {
        setStatus('✅ Работа успешно отправлена!');
        window.Telegram.WebApp.HapticFeedback.notificationOccurred('success');
      } else {
        setStatus('❌ Ошибка при загрузке');
      }
    } catch (err) {
      setStatus('❌ Сервер недоступен (CORS?)');
    }
  };

  return (
    <div className="App">
      <img src="cat.jpg" alt="SAPSR Logo" className="logo" />
      <h1>SAPSR</h1>

{/* --- ЭКРАН ВЫБОРА РОЛИ --- */}
      {step === 'role' && (
        <div className="screen fade-in">
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
          <button className="skip-btn" onClick={skipRegistration}>
            Пропустить регистрацию ➔
          </button>
        </div>
      )}

{/* --- ЭКРАН ВВОДА КОДА --- */}
{step === 'code' && (
  <div className="screen fade-in">
    <p className="description">
      Введите код доступа для роли <br/>
      <b>{userRole === 'student' ? 'Студент' : 'Преподаватель'}</b>
    </p>

    <form onSubmit={handleCodeSubmit} className="code-form-container">
      <input
        type="text"
        className="code-input"
        placeholder="Код..."
        value={accessCode}
        onChange={(e) => setAccessCode(e.target.value)}
        autoFocus
      />

      <div className="vertical-button-group">
        <button type="submit" className="submit-btn">
          <span className="btn-icon">✅</span>
          <span>Войти</span>
        </button>

        <button type="button" className="secondary-btn" onClick={() => setStep('role')}>
          <span className="btn-icon">⬅️</span>
          <span>Назад</span>
        </button>
      </div>
    </form>
  </div>
)}

      {/* --- ОСНОВНОЙ ЭКРАН ЗАГРУЗКИ --- */}
      {step === 'main' && (
        <div className="screen fade-in">
          <p className="description">
            Система Автоматической Проверки <br/> Самостоятельных Работ
          </p>
          <div className="upload-container">
            <form onSubmit={handleSubmit}>
              <label htmlFor="file-upload" className="custom-file-upload">
                <span style={{fontSize: '30px'}}>📁</span>
                <span>{file ? 'Сменить файл' : 'Нажмите, чтобы выбрать файл'}</span>
                {file && <div className="file-name">{file.name}</div>}
              </label>
              <input id="file-upload" type="file" onChange={handleFileChange} />
              <button type="submit" className="submit-btn" disabled={!file} style={{marginTop: '20px'}}>
                Отправить на проверку
              </button>
            </form>
          </div>
          {status && <div className="status">{status}</div>}
          <button className="back-link" onClick={() => setStep('role')}>Сменить роль</button>
        </div>
      )}
    </div>
  );
}

export default App;