import { useState, useEffect } from 'react'
import './App.css'
import { initData, initDataSource, telegramScriptLoaded } from './telegram'
import { CUSTOM_PROVIDER_ID, CUSTOM_STEPS, PROVIDERS, detectProvider, findProvider } from './providers'

type MailboxStatus = 'CONNECTING' | 'OK' | 'ERROR';

interface Account {
  id: number;
  name: string;
  email: string;
  host: string;
  status?: MailboxStatus;
  problem?: string | null;
}

type Notice = { kind: 'info' | 'ok' | 'error'; text: string } | null;

const REQUEST_TIMEOUT_MS = 20000;
// Добавление включает проверку входа на почтовом сервере: это может занять до минуты
const ADD_ACCOUNT_TIMEOUT_MS = 70000;
// Пока приложение открыто, статусы ящиков обновляются сами
const REFRESH_INTERVAL_MS = 15000;

function describeError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return 'Сервер не ответил вовремя. Попробуйте ещё раз.';
  }
  return 'Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.';
}

// Для 401 показываем, пришли ли вообще данные от Telegram
function httpError(prefix: string, status: number): string {
  if (status !== 401) return `${prefix} (HTTP ${status})`;
  const hashLength = window.location.hash.length;
  return `${prefix}: Telegram не передал данные входа. Закройте приложение и откройте его из чата с ботом. ` +
    `(данные: ${initData.length} симв., источник: ${initDataSource}, скрипт Telegram: ${telegramScriptLoaded ? 'да' : 'нет'}, hash: ${hashLength})`;
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

function statusLabel(account: Account): string {
  switch (account.status) {
    case 'OK':
      return 'Письма приходят';
    case 'ERROR':
      return account.problem ?? 'Не удаётся подключиться';
    default:
      return 'Подключается…';
  }
}

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);
  const [notice, setNotice] = useState<Notice>(
    initData ? null : { kind: 'error', text: 'Откройте приложение из чата с ботом в Telegram: без этого вход невозможен.' }
  );

  // Форма
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [providerId, setProviderId] = useState('');
  const [providerTouched, setProviderTouched] = useState(false);
  const [customHost, setCustomHost] = useState('');

  // Относительный путь: в разработке его проксирует Vite, в проде - Caddy.
  const API_URL = import.meta.env.VITE_API_URL ?? '/api';

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

  const fetchAccounts = async (silent = false) => {
    try {
      const response = await request('/accounts');
      if (response.ok) {
        setAccounts(await response.json());
      } else if (!silent) {
        setNotice({ kind: 'error', text: await errorText(response, 'Не удалось загрузить ящики') });
      }
    } catch (error) {
      if (!silent) setNotice({ kind: 'error', text: describeError(error) });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAccounts();
    const timer = setInterval(() => fetchAccounts(true), REFRESH_INTERVAL_MS);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Сервис определяется по адресу, пока пользователь не выбрал его сам
  const onEmailChange = (value: string) => {
    setEmail(value);
    if (!providerTouched) {
      setProviderId(detectProvider(value)?.id ?? '');
    }
  };

  const provider = findProvider(providerId);
  const isCustom = providerId === CUSTOM_PROVIDER_ID;
  const host = isCustom ? customHost.trim() : provider?.host ?? '';
  const steps = provider?.steps ?? (isCustom ? CUSTOM_STEPS : null);

  const handleAddAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!host) {
      setNotice({ kind: 'error', text: 'Выберите почтовый сервис или укажите IMAP-сервер.' });
      return;
    }

    setSubmitting(true);
    setNotice({ kind: 'info', text: 'Проверяем вход в почту. Это займёт несколько секунд.' });
    try {
      const response = await request('/accounts', 'POST', {
        name: name.trim(),
        host,
        port: 993,
        username: email.trim(),
        appPassword: password.trim(),
      }, ADD_ACCOUNT_TIMEOUT_MS);

      if (response.ok) {
        setNotice({ kind: 'ok', text: 'Ящик подключён. Новые письма будут приходить в чат с ботом.' });
        setEmail(''); setPassword(''); setName(''); setProviderId(''); setProviderTouched(false); setCustomHost('');
        await fetchAccounts();
      } else {
        setNotice({ kind: 'error', text: await errorText(response, 'Не удалось подключить ящик') });
      }
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
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
        setNotice({ kind: 'ok', text: `Ящик ${account.email} отключён.` });
        await fetchAccounts();
      } else {
        setNotice({ kind: 'error', text: await errorText(response, 'Не удалось отключить ящик') });
      }
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <main className="app">
      <header className="masthead">
        <div className="airmail-band" aria-hidden="true" />
        <h1>Почта в Telegram</h1>
        <p className="lede">Новые письма из ваших ящиков приходят в чат с ботом.</p>
      </header>

      {notice && (
        <p className={`notice notice-${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      )}

      <section className="section" aria-labelledby="mailboxes-title">
        <h2 id="mailboxes-title" className="section-title">
          Мои ящики{accounts.length > 0 && <span className="count">{accounts.length}</span>}
        </h2>

        {loading ? (
          <p className="empty">Загружаем ящики…</p>
        ) : accounts.length === 0 ? (
          <p className="empty">Пока ни одного ящика. Подключите первый ниже, и новые письма начнут приходить в этот чат.</p>
        ) : (
          <ul className="mailbox-list">
            {accounts.map((acc) => (
              <li key={acc.id} className="mailbox">
                <span className={`status-dot status-${(acc.status ?? 'CONNECTING').toLowerCase()}`} aria-hidden="true" />
                <div className="mailbox-info">
                  <span className="mailbox-name">{acc.name !== acc.email ? acc.name : acc.email}</span>
                  {acc.name !== acc.email && <span className="mailbox-address">{acc.email}</span>}
                  <span className={`mailbox-status status-text-${(acc.status ?? 'CONNECTING').toLowerCase()}`}>
                    {statusLabel(acc)}
                  </span>
                </div>
                <button
                  type="button"
                  className={confirmDeleteId === acc.id ? 'remove confirm' : 'remove'}
                  disabled={deletingId === acc.id}
                  onClick={() => handleDelete(acc)}
                  onBlur={() => setConfirmDeleteId((current) => (current === acc.id ? null : current))}
                >
                  {deletingId === acc.id ? 'Отключаем…' : confirmDeleteId === acc.id ? 'Отключить?' : 'Отключить'}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="section" aria-labelledby="add-title">
        <h2 id="add-title" className="section-title">Подключить ящик</h2>

        <form onSubmit={handleAddAccount} className="add-form">
          <label className="field">
            <span className="field-label">Адрес почты</span>
            <input
              type="email"
              inputMode="email"
              placeholder="name@mail.ru"
              autoComplete="off"
              value={email}
              onChange={e => onEmailChange(e.target.value)}
              required
            />
          </label>

          <label className="field">
            <span className="field-label">Почтовый сервис</span>
            <select
              value={providerId}
              onChange={e => { setProviderId(e.target.value); setProviderTouched(true); }}
              required
            >
              <option value="" disabled>Выберите сервис</option>
              {PROVIDERS.map((item) => (
                <option key={item.id} value={item.id}>{item.title}</option>
              ))}
              <option value={CUSTOM_PROVIDER_ID}>Другой сервис</option>
            </select>
          </label>

          {isCustom && (
            <label className="field">
              <span className="field-label">IMAP-сервер</span>
              <input
                placeholder="imap.example.com"
                autoComplete="off"
                value={customHost}
                onChange={e => setCustomHost(e.target.value)}
                required
              />
            </label>
          )}

          <aside className="app-password" aria-labelledby="app-password-title">
            <h3 id="app-password-title">Нужен пароль приложения, а не пароль от почты</h3>
            <p>
              Почтовые сервисы не пускают сторонние программы по обычному паролю. Создайте отдельный
              пароль приложения: это займёт минуту, а отозвать его можно в любой момент, не меняя пароль от почты.
            </p>
            {steps ? (
              <ol className="steps">
                {steps.map((step) => <li key={step}>{step}</li>)}
              </ol>
            ) : (
              <p className="steps-placeholder">Введите адрес почты, и здесь появится инструкция для вашего сервиса.</p>
            )}
          </aside>

          <label className="field">
            <span className="field-label">Пароль приложения</span>
            <input
              type="password"
              autoComplete="off"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
            />
            {provider && <span className="field-hint">{provider.passwordHint}</span>}
          </label>

          <label className="field">
            <span className="field-label">Название <span className="optional">(необязательно)</span></span>
            <input placeholder="Например, «Работа»" value={name} onChange={e => setName(e.target.value)} />
          </label>

          <button type="submit" className="primary" disabled={submitting}>
            {submitting ? 'Проверяем вход…' : 'Подключить ящик'}
          </button>
        </form>
      </section>
    </main>
  )
}

export default App
