import { useState, useEffect } from 'react'
import './App.css'
import { initData, initDataSource, telegramScriptLoaded } from './telegram'
import { CUSTOM_PROVIDER_ID, PROVIDERS, detectProvider, findProvider } from './providers'

interface Account {
  id: number;
  name: string;
  email: string;
  host: string;
}

type Status = { kind: 'info' | 'ok' | 'error'; text: string } | null;

// Добавление ящика включает проверку входа на почтовом сервере: это может занять до минуты
const REQUEST_TIMEOUT_MS = 20000;
const ADD_ACCOUNT_TIMEOUT_MS = 70000;

function describeError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return 'Сервер не ответил вовремя. Попробуйте ещё раз';
  }
  return 'Нет связи с сервером';
}

// Для 401 показываем, пришли ли вообще данные от Telegram
function httpError(prefix: string, status: number): string {
  if (status !== 401) return `${prefix} (HTTP ${status})`;
  const hashLength = window.location.hash.length;
  return `${prefix} (HTTP 401). Telegram-данные: ${initData.length} симв. (источник: ${initDataSource}); ` +
    `скрипт Telegram: ${telegramScriptLoaded ? 'загружен' : 'не загружен'}, hash в адресе: ${hashLength} симв.`;
}

// Сервер присылает понятный текст ошибки в поле error
async function errorText(response: Response, prefix: string): Promise<string> {
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string' && response.status !== 401) {
      return body.error;
    }
  } catch {
    // тело не JSON
  }
  return httpError(prefix, response.status);
}

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);
  const [status, setStatus] = useState<Status>(
    initData ? null : { kind: 'error', text: 'Откройте приложение из Telegram: без него вход невозможен' }
  );

  // Состояния формы
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [providerId, setProviderId] = useState('');
  const [providerTouched, setProviderTouched] = useState(false);
  const [customHost, setCustomHost] = useState('');

  // Относительный путь: в разработке его проксирует Vite, в проде - reverse proxy.
  // Для нестандартного адреса бэкенда задай VITE_API_URL в frontend/.env.local
  const API_URL = import.meta.env.VITE_API_URL ?? '/api';

  // fetch с таймаутом: кнопка не должна "висеть" бесконечно
  const request = async (
    path: string,
    method: 'GET' | 'POST' | 'DELETE' = 'GET',
    body?: unknown,
    timeoutMs: number = REQUEST_TIMEOUT_MS,
  ) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(`${API_URL}${path}`, {
        method,
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          'X-Telegram-Init-Data': initData
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
        setStatus({ kind: 'error', text: await errorText(response, 'Не удалось загрузить список ящиков') });
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

  // Сервис определяется по адресу почты, пока пользователь не выбрал его вручную
  const onEmailChange = (value: string) => {
    setEmail(value);
    if (!providerTouched) {
      setProviderId(detectProvider(value)?.id ?? '');
    }
  };

  const provider = findProvider(providerId);
  const isCustom = providerId === CUSTOM_PROVIDER_ID;
  const host = isCustom ? customHost.trim() : provider?.host ?? '';

  const handleAddAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!host) {
      setStatus({ kind: 'error', text: 'Выберите почтовый сервис или укажите IMAP-сервер' });
      return;
    }

    setSubmitting(true);
    setStatus({ kind: 'info', text: 'Проверяем вход в почту...' });
    try {
      const response = await request('/accounts', 'POST', {
        name: name.trim(),
        host,
        port: 993,
        username: email.trim(),
        appPassword: password,
      }, ADD_ACCOUNT_TIMEOUT_MS);

      if (response.ok) {
        setStatus({ kind: 'ok', text: 'Ящик подключён! Новые письма будут приходить в чат с ботом.' });
        setEmail(''); setPassword(''); setName(''); setProviderId(''); setProviderTouched(false); setCustomHost('');
        await fetchAccounts();
      } else {
        setStatus({ kind: 'error', text: await errorText(response, 'Ошибка при добавлении ящика') });
      }
    } catch (error) {
      console.error('Ошибка добавления:', error);
      setStatus({ kind: 'error', text: describeError(error) });
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (account: Account) => {
    // Удаление в два нажатия: первое спрашивает подтверждение
    if (confirmDeleteId !== account.id) {
      setConfirmDeleteId(account.id);
      return;
    }

    setConfirmDeleteId(null);
    setDeletingId(account.id);
    try {
      const response = await request(`/accounts/${account.id}`, 'DELETE');
      if (response.ok) {
        setStatus({ kind: 'ok', text: `Ящик ${account.email} отключён` });
        await fetchAccounts();
      } else {
        setStatus({ kind: 'error', text: await errorText(response, 'Не удалось удалить ящик') });
      }
    } catch (error) {
      setStatus({ kind: 'error', text: describeError(error) });
    } finally {
      setDeletingId(null);
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
              {accounts.map((acc) => (
                <li key={acc.id} className="account-item">
                  <div className="account-info">
                    <strong>{acc.name}</strong>
                    <span>{acc.email}</span>
                    <span className="host-text">{acc.host}</span>
                  </div>
                  <button
                    type="button"
                    className={confirmDeleteId === acc.id ? 'delete-button confirm' : 'delete-button'}
                    disabled={deletingId === acc.id}
                    onClick={() => handleDelete(acc)}
                  >
                    {deletingId === acc.id ? '...' : confirmDeleteId === acc.id ? 'Точно?' : 'Удалить'}
                  </button>
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
        <input
          type="email"
          placeholder="Адрес почты"
          autoComplete="off"
          value={email}
          onChange={e => onEmailChange(e.target.value)}
          required
        />
        <input
          type="password"
          placeholder="Пароль приложения"
          autoComplete="off"
          value={password}
          onChange={e => setPassword(e.target.value)}
          required
        />

        <select
          value={providerId}
          onChange={e => { setProviderId(e.target.value); setProviderTouched(true); }}
          required
        >
          <option value="" disabled>Почтовый сервис</option>
          {PROVIDERS.map((item) => (
            <option key={item.id} value={item.id}>{item.title}</option>
          ))}
          <option value={CUSTOM_PROVIDER_ID}>Другой (указать сервер)</option>
        </select>

        {isCustom && (
          <input
            placeholder="IMAP-сервер, например imap.example.com"
            value={customHost}
            onChange={e => setCustomHost(e.target.value)}
            required
          />
        )}

        {provider && <p className="hint-text">{provider.hint}</p>}

        <input placeholder="Название (необязательно)" value={name} onChange={e => setName(e.target.value)} />

        <button type="submit" disabled={submitting}>{submitting ? 'Проверяем...' : 'Подключить'}</button>
      </form>
    </div>
  )
}

export default App
