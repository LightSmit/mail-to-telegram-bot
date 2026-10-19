import { useState, useEffect } from 'react'
import WebApp from '@twa-dev/sdk'
import './App.css'

interface Account {
  name: string;
  email: string;
  host: string;
}

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);

  // Состояния формы
  const [name, setName] = useState('');
  const [host, setHost] = useState('');
  const [port, setPort] = useState<number | ''>('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const API_URL = 'http://localhost:8080/api';

  useEffect(() => {
    fetchAccounts();
  }, []);

  const fetchAccounts = async () => {
    try {
      const response = await fetch(`${API_URL}/accounts`, {
        headers: {
          'X-Telegram-Init-Data': WebApp.initData || ''
        }
      });
      if (response.ok) {
        const data = await response.json();
        setAccounts(data);
      }
    } catch (error) {
      console.error("Ошибка загрузки:", error);
    } finally {
      setLoading(false);
    }
  };

  const handleAddAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const response = await fetch(`${API_URL}/accounts`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Telegram-Init-Data': WebApp.initData || ''
        },
        body: JSON.stringify({ name, host, port: Number(port), username, appPassword: password })
      });

      if (response.ok) {
        WebApp.showAlert('Ящик успешно добавлен!');
        fetchAccounts(); // Обновляем список
        // Очищаем форму
        setName(''); setHost(''); setPort(''); setUsername(''); setPassword('');
      } else {
        WebApp.showAlert('Ошибка при добавлении ящика');
      }
    } catch (error) {
      WebApp.showAlert('Ошибка сети. Проверьте соединение с сервером.');
    }
  };

  return (
    <div className="container">
      <h1>Мои ящики</h1>
      
      {loading ? <p className="status-text">Загрузка...</p> : (
        <div className="account-list">
          {accounts.length === 0 ? <p className="status-text">Нет подключенных ящиков</p> : (
            <ul>
              {accounts.map((acc, index) => (
                <li key={index} className="account-item">
                  <strong>{acc.name}</strong>
                  <span>{acc.email}</span>
                  <span className="host-text">{acc.host}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <h2>Добавить ящик</h2>
      <form onSubmit={handleAddAccount} className="add-form">
        <input placeholder="Название (напр. Рабочий)" value={name} onChange={e => setName(e.target.value)} required />
        <input placeholder="IMAP Сервер (напр. imap.mail.ru)" value={host} onChange={e => setHost(e.target.value)} required />
        <input type="number" placeholder="Порт (обычно 993)" value={port} onChange={e => setPort(Number(e.target.value))} required />
        <input type="email" placeholder="Email / Логин" value={username} onChange={e => setUsername(e.target.value)} required />
        <input type="password" placeholder="Пароль приложения" value={password} onChange={e => setPassword(e.target.value)} required />
        <button type="submit">Подключить</button>
      </form>
    </div>
  )
}

export default App