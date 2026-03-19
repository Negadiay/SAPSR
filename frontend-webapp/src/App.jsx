import { useState, useEffect } from 'react';
import './App.css';

function App() {
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState('');
  const [user, setUser] = useState(null);

  // Инициализация Telegram Web App
  useEffect(() => {
    const tg = window.Telegram.WebApp;
    tg.ready(); // Сообщаем TG, что приложение загрузилось
    setUser(tg.initDataUnsafe?.user); // Получаем данные пользователя (имя, id)
  }, []);

  const handleFileChange = (e) => {
    setFile(e.target.files[0]);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!file) return alert('Выберите файл');

    const formData = new FormData();
    formData.append('file', file);

    // Добавляем ID пользователя из Telegram, чтобы Java знала чья это работа
    if (user) {
      formData.append('userId', user.id);
    }

    try {
      setStatus('Загрузка...');
      const response = await fetch('http://localhost:8080/upload', {
        method: 'POST',
        body: formData,
      });

      if (response.ok) {
        setStatus('✅ Успешно отправлено!');
        // Можно закрыть мини-приложение через 2 секунды после успеха
        setTimeout(() => window.Telegram.WebApp.close(), 2000);
      } else {
        setStatus('❌ Ошибка сервера');
      }
    } catch (err) {
      setStatus('❌ Ошибка сети (проверьте CORS на Java)');
    }
  };

  return (
    <div className="App" style={{ padding: '20px', textAlign: 'center' }}>
      {user && <p>Привет, <b>{user.first_name}</b>!</p>}

      <h2>Загрузка работы</h2>

      <form onSubmit={handleSubmit}>
        <div style={{ marginBottom: '20px' }}>
          <input type="file" onChange={handleFileChange} />
        </div>

        <button
          type="submit"
          style={{
            backgroundColor: 'var(--tg-theme-button-color, #2481cc)', // Цвет из темы TG
            color: 'var(--tg-theme-button-text-color, #ffffff)',
            border: 'none',
            padding: '10px 20px',
            borderRadius: '8px',
            width: '100%'
          }}
        >
          Отправить на проверку
        </button>
      </form>

      <p>{status}</p>
    </div>
  );
}

export default App;