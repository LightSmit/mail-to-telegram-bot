import { useState, useEffect } from 'react'
import WebApp from '@twa-dev/sdk'
import './App.css'

interface Account {
  name: string;
  email: string;
  host: string;
}

type Status = { kind: 'info' | 'ok' | 'error'; text: string } | null;

const REQUEST_TIMEOUT_MS = 20000;

function describeError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return 'Сервер не ответил за 20 секунд';
  }
  return 'Нет связи с сервером';
}

// Для 401 показываем, пришли ли вообще данные от Telegram
function httpError(prefix: string, status: number): string {
  if (status !== 401) return `${prefix} (HTTP ${status})`;
  const length = WebApp.initData?.length ?? 0;
  const userId = WebApp.initDataUnsafe?.user?.id;
  const hashLength = window.location.hash.length;
  return `${prefix} (HTTP 401). Telegram-данные: ${length} симв., user: ${userId ?? 'нет'}; ` +
    `платформа: ${WebApp.platform}, версия: ${WebApp.version}, hash в адресе: ${hashLength} симв.`;
}

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState<Status>(
    WebApp.initData ? null : { kind: 'error', text: 'Откройте приложение из Telegram: без него вход невозможен' }
  );

  // Состояния формы
  const [name, setName] = useState('');
  const [host, setHost] = useState('');
  const [port, setPort] = useState<number | ''>('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  // Относительный путь: в разработке его проксирует Vite, в проде - reverse proxy.
  // Для нестандартного адреса бэкенда задай VITE_API_URL в frontend/.env.local
  const API_URL = import.meta.env.VITE_API_URL ?? '/api';

  // fetch с таймаутом: кнопка не должна "висеть" бесконечно
  const request = async (path: string, method: 'GET' | 'POST' = 'GET', body?: unknown) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      return await fetch(`${API_URL}${path}`, {
        method,
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          'X-Telegram-Init-Data': WebApp.initData || ''
        },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } finally {
      clearTimeout(timer);
    }
  };

  const fetchAccounts = async () => {
    try {
      const response = await request('/accounts');
      if (response.ok) {
        setAccounts(await response.json());
      } else {
        setStatus({ kind: 'error', text: httpError('Не удалось загрузить список ящиков', response.status) });
      }
    } catch (error) {
      console.error('Ошибка загрузки:', error);
      setStatus({ kind: 'error', text: describeError(error) });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAccounts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleAddAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setStatus({ kind: 'info', text: 'Подключаем ящик...' });
    try {
      const response = await request('/accounts', 'POST', {
        name, host, port: Number(port), username, appPassword: password
      });

      if (response.ok) {
        setStatus({ kind: 'ok', text: 'Ящик успешно добавлен!' });
        setName(''); setHost(''); setPort(''); setUsername(''); setPassword('');
        await fetchAccounts();
      } else {
        setStatus({ kind: 'error', text: httpError('Ошибка при добавлении ящика', response.status) });
      }
    } catch (error) {
      console.error('Ошибка добавления:', error);
      setStatus({ kind: 'error', text: describeError(error) });
    } finally {
      setSubmitting(false);
    }
  };

  const statusColor = status?.kind === 'error' ? '#e5484d' : status?.kind === 'ok' ? '#30a46c' : undefined;

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

      {status && (
        <p className="status-text" style={{ color: statusColor, fontSize: 15 }}>{status.text}</p>
      )}

      <h2>Добавить ящик</h2>
      <form onSubmit={handleAddAccount} className="add-form">
        <input placeholder="Название (напр. Рабочий)" value={name} onChange={e => setName(e.target.value)} required />
        <input placeholder="IMAP Сервер (напр. imap.mail.ru)" value={host} onChange={e => setHost(e.target.value)} required />
        <input type="number" placeholder="Порт (обычно 993)" value={port} onChange={e => setPort(Number(e.target.value))} required />
        <input type="email" placeholder="Email / Логин" value={username} onChange={e => setUsername(e.target.value)} required />
        <input type="password" placeholder="Пароль приложения" value={password} onChange={e => setPassword(e.target.value)} required />
        <button type="submit" disabled={submitting}>{submitting ? 'Подключаем...' : 'Подключить'}</button>
      </form>
    </div>
  )
}

export default App
