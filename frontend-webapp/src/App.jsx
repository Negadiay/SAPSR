import { useState, useEffect } from 'react';
import './App.css';

function App() {
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState('');

  useEffect(() => {
    window.Telegram.WebApp.ready();
    window.Telegram.WebApp.expand(); // Разворачивает приложение на весь экран
  }, []);

  const handleFileChange = (e) => {
    if (e.target.files[0]) {
      setFile(e.target.files[0]);
      setStatus(''); // Сбрасываем статус при выборе нового файла
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!file) return;

    const formData = new FormData();
    formData.append('file', file);

    // Получаем ID пользователя из Telegram
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
      {/* Логотип (замени путь на свою картинку) */}
      <img src="/cat.jpg" alt="SAPSR Logo" className="logo" />

      <h1>SAPSR</h1>
      <p className="description">
        Система Автоматической Проверки <br/> Самостоятельных Работ
      </p>

      <div className="upload-container">
        <form onSubmit={handleSubmit}>
          {/* Скрытый input и стилизованный label под него */}
          <label htmlFor="file-upload" className="custom-file-upload">
            <span style={{fontSize: '30px'}}>📁</span>
            <span>{file ? 'Сменить файл' : 'Нажмите, чтобы выбрать файл'}</span>
            {file && <div className="file-name">{file.name}</div>}
          </label>

          <input
            id="file-upload"
            type="file"
            onChange={handleFileChange}
            accept=".py,.js,.java,.txt,.pdf"
          />

          <button
            type="submit"
            className="submit-btn"
            disabled={!file}
            style={{marginTop: '20px'}}
          >
            Отправить на проверку
          </button>
        </form>
      </div>

      {status && <div className="status">{status}</div>}
    </div>
  );
}

export default App;