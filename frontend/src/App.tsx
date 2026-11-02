import { useState, useEffect } from 'react'
import './App.css'
import { initData } from './telegram'
import { lang, rememberLang, t, type Lang } from './i18n'
import { LanguageSwitch } from './LanguageSwitch'
import { FiltersPanel, withDefaults, type MailFilters } from './FiltersPanel'
import { PasswordPanel } from './PasswordPanel'
import { NotificationsSection, type NotificationSettings } from './NotificationsSection'
import { MailboxView } from './MailboxView'
import { DataSection } from './DataSection'
import { CUSTOM_PROVIDER_ID, CUSTOM_STEPS, PROVIDERS, detectProvider, findProvider, providerHint, providerSteps, providerTitle } from './providers'

type MailboxStatus = 'CONNECTING' | 'OK' | 'ERROR';

interface Account {
  id: number;
  name: string;
  email: string;
  host: string;
  status?: MailboxStatus;
  problem?: string | null;
  filters?: MailFilters;
}

type Notice = { kind: 'info' | 'ok' | 'error'; text: string } | null;

const REQUEST_TIMEOUT_MS = 20000;
// Добавление включает проверку входа на почтовом сервере: это может занять до минуты
const ADD_ACCOUNT_TIMEOUT_MS = 70000;
// Пока приложение открыто, статусы ящиков обновляются сами
const REFRESH_INTERVAL_MS = 15000;

function describeError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return t.serverTimeout;
  }
  return t.noConnection;
}

// 401: Telegram не передал данные входа (приложение открыто не из чата с ботом)
function httpError(prefix: string, status: number): string {
  if (status === 401) return t.reopenFromChat(prefix);
  return `${prefix} (HTTP ${status})`;
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
  if (account.filters?.paused) return t.statusPaused;
  switch (account.status) {
    case 'OK':
      return t.statusOk;
    case 'ERROR':
      return account.problem ?? t.statusError;
    default:
      return t.statusConnecting;
  }
}

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);
  const [filtersOpenId, setFiltersOpenId] = useState<number | null>(null);
  // Открытый ящик: просмотр его писем вместо главного экрана
  const [browsingId, setBrowsingId] = useState<number | null>(null);
  const [notice, setNotice] = useState<Notice>(
    initData ? null : { kind: 'error', text: t.openFromTelegram }
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
    method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET',
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
        setNotice({ kind: 'error', text: await errorText(response, t.loadMailboxesFailed) });
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
  const steps = provider ? providerSteps(provider) : isCustom ? CUSTOM_STEPS : null;

  const handleAddAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!host) {
      setNotice({ kind: 'error', text: t.chooseProvider });
      return;
    }

    setSubmitting(true);
    setNotice({ kind: 'info', text: t.checkingLogin });
    try {
      const response = await request('/accounts', 'POST', {
        name: name.trim(),
        host,
        port: 993,
        username: email.trim(),
        appPassword: password.trim(),
      }, ADD_ACCOUNT_TIMEOUT_MS);

      if (response.ok) {
        setNotice({ kind: 'ok', text: t.mailboxAdded });
        setEmail(''); setPassword(''); setName(''); setProviderId(''); setProviderTouched(false); setCustomHost('');
        await fetchAccounts();
      } else {
        setNotice({ kind: 'error', text: await errorText(response, t.addFailed) });
      }
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    } finally {
      setSubmitting(false);
    }
  };

  const handleSaveFilters = async (account: Account, filters: MailFilters): Promise<boolean> => {
    try {
      const response = await request(`/accounts/${account.id}/filters`, 'PUT', filters);
      if (response.ok) {
        const saved = withDefaults(await response.json());
        setAccounts((current) => current.map((item) => (item.id === account.id ? { ...item, filters: saved } : item)));
        setNotice({ kind: 'ok', text: t.filtersSaved(account.email) });
        return true;
      }
      setNotice({ kind: 'error', text: await errorText(response, t.filtersFailed) });
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    }
    return false;
  };

  const handleUpdatePassword = async (account: Account, newPassword: string): Promise<boolean> => {
    setNotice({ kind: 'info', text: t.checkingPassword(account.email) });
    try {
      const response = await request(`/accounts/${account.id}/password`, 'PUT', { appPassword: newPassword }, ADD_ACCOUNT_TIMEOUT_MS);
      if (response.ok) {
        setNotice({ kind: 'ok', text: t.passwordUpdated(account.email) });
        await fetchAccounts(true);
        return true;
      }
      setNotice({ kind: 'error', text: await errorText(response, t.passwordFailed) });
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    }
    return false;
  };

  const loadNotificationSettings = async (): Promise<NotificationSettings | null> => {
    try {
      const response = await request('/settings');
      return response.ok ? await response.json() : null;
    } catch {
      return null;
    }
  };

  const saveNotificationSettings = async (settings: NotificationSettings): Promise<NotificationSettings | null> => {
    try {
      const response = await request('/settings', 'PUT', settings);
      if (response.ok) {
        setNotice({ kind: 'ok', text: t.notifySaved });
        return await response.json();
      }
      setNotice({ kind: 'error', text: await errorText(response, t.notifyFailed) });
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    }
    return null;
  };

  const handleDeleteAllData = async (): Promise<boolean> => {
    try {
      const response = await request('/me', 'DELETE');
      if (response.ok) {
        setAccounts([]);
        setNotice({ kind: 'ok', text: t.dataDeleted });
        return true;
      }
      setNotice({ kind: 'error', text: await errorText(response, t.dataDeleteFailed) });
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    }
    return false;
  };

  const saveLanguage = async (value: Lang): Promise<boolean> => {
    try {
      const response = await request('/language', 'PUT', { language: value });
      if (response.ok) return true;
      setNotice({ kind: 'error', text: await errorText(response, t.noServer) });
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    }
    return false;
  };

  // Язык мог быть выбран в боте (/language) или на другом устройстве: сверяемся с сервером один раз
  useEffect(() => {
    if (!initData) return;
    (async () => {
      try {
        const response = await request('/language');
        if (!response.ok) return;
        const { language } = await response.json();
        // Перезагружаемся, только если язык удалось запомнить: иначе была бы бесконечная перезагрузка
        if ((language === 'ru' || language === 'en') && language !== lang && rememberLang(language)) {
          window.location.reload();
        }
      } catch {
        // без связи остаемся на текущем языке
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
        setNotice({ kind: 'ok', text: t.mailboxRemoved(account.email) });
        await fetchAccounts();
      } else {
        setNotice({ kind: 'error', text: await errorText(response, t.removeFailed) });
      }
    } catch (error) {
      setNotice({ kind: 'error', text: describeError(error) });
    } finally {
      setDeletingId(null);
    }
  };

  const browsing = accounts.find((acc) => acc.id === browsingId);
  if (browsing) {
    return (
      <main className="app">
        <MailboxView account={browsing} request={request} onClose={() => setBrowsingId(null)} />
      </main>
    );
  }

  return (
    <main className="app">
      <header className="masthead">
        <div className="airmail-band" aria-hidden="true" />
        <div className="title-row">
          <h1>{t.title}</h1>
          <LanguageSwitch onChange={saveLanguage} />
        </div>
        <p className="lede">{t.lede}</p>
      </header>

      {notice && (
        <p className={`notice notice-${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      )}

      <section className="section" aria-labelledby="mailboxes-title">
        <h2 id="mailboxes-title" className="section-title">
          {t.myMailboxes}{accounts.length > 0 && <span className="count">{accounts.length}</span>}
        </h2>

        {loading ? (
          <p className="empty">{t.loadingMailboxes}</p>
        ) : accounts.length === 0 ? (
          <p className="empty">{t.noMailboxes}</p>
        ) : (
          <ul className="mailbox-list">
            {accounts.map((acc) => (
              <li key={acc.id} className="mailbox-item">
                <div className="mailbox">
                  <span className={`status-dot status-${acc.filters?.paused ? 'paused' : (acc.status ?? 'CONNECTING').toLowerCase()}`} aria-hidden="true" />
                  <button
                    type="button"
                    className="mailbox-info mailbox-open"
                    onClick={() => setBrowsingId(acc.id)}
                    aria-label={t.openMailbox(acc.email)}
                  >
                    <span className="mailbox-name">{acc.name !== acc.email ? acc.name : acc.email}</span>
                    {acc.name !== acc.email && <span className="mailbox-address">{acc.email}</span>}
                    <span className={`mailbox-status status-text-${acc.filters?.paused ? 'paused' : (acc.status ?? 'CONNECTING').toLowerCase()}`}>
                      {statusLabel(acc)}
                    </span>
                    <span className="mailbox-open-hint">{t.emailsLink}</span>
                  </button>
                  <div className="mailbox-actions">
                    <button
                      type="button"
                      className="link-button"
                      aria-expanded={filtersOpenId === acc.id}
                      onClick={() => setFiltersOpenId((current) => (current === acc.id ? null : acc.id))}
                    >
                      {acc.status === 'ERROR' ? t.fix : t.settings}
                    </button>
                    <button
                      type="button"
                      className={confirmDeleteId === acc.id ? 'remove confirm' : 'remove'}
                      disabled={deletingId === acc.id}
                      onClick={() => handleDelete(acc)}
                      onBlur={() => setConfirmDeleteId((current) => (current === acc.id ? null : current))}
                    >
                      {deletingId === acc.id ? t.disconnecting : confirmDeleteId === acc.id ? t.disconnectConfirm : t.disconnect}
                    </button>
                  </div>
                </div>
                {filtersOpenId === acc.id && (
                  <div className={acc.status === 'ERROR' ? 'settings settings-broken' : 'settings'}>
                    <FiltersPanel
                      initial={withDefaults(acc.filters)}
                      onSave={(filters) => handleSaveFilters(acc, filters)}
                      onClose={() => setFiltersOpenId(null)}
                    />
                    <PasswordPanel
                      host={acc.host}
                      broken={acc.status === 'ERROR'}
                      onSave={(newPassword) => handleUpdatePassword(acc, newPassword)}
                    />
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {accounts.length > 0 && (
        <NotificationsSection load={loadNotificationSettings} save={saveNotificationSettings} />
      )}

      <section className="section" aria-labelledby="add-title">
        <h2 id="add-title" className="section-title">{t.addTitle}</h2>

        <form onSubmit={handleAddAccount} className="add-form">
          <label className="field">
            <span className="field-label">{t.emailLabel}</span>
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
            <span className="field-label">{t.providerLabel}</span>
            <select
              value={providerId}
              onChange={e => { setProviderId(e.target.value); setProviderTouched(true); }}
              required
            >
              <option value="" disabled>{t.chooseProviderOption}</option>
              {PROVIDERS.map((item) => (
                <option key={item.id} value={item.id}>{providerTitle(item)}</option>
              ))}
              <option value={CUSTOM_PROVIDER_ID}>{t.otherProvider}</option>
            </select>
          </label>

          {isCustom && (
            <label className="field">
              <span className="field-label">{t.imapServer}</span>
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
            <h3 id="app-password-title">{t.appPasswordTitle}</h3>
            <p>{t.appPasswordLede}</p>
            {steps ? (
              <ol className="steps">
                {steps.map((step) => <li key={step}>{step}</li>)}
              </ol>
            ) : (
              <p className="steps-placeholder">{t.stepsPlaceholder}</p>
            )}
          </aside>

          <label className="field">
            <span className="field-label">{t.appPasswordLabel}</span>
            <input
              type="password"
              autoComplete="off"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
            />
            {provider && <span className="field-hint">{providerHint(provider)}</span>}
          </label>

          <label className="field">
            <span className="field-label">{t.nameLabel} <span className="optional">{t.optional}</span></span>
            <input placeholder={t.namePlaceholder} value={name} onChange={e => setName(e.target.value)} />
          </label>

          <button type="submit" className="primary" disabled={submitting}>
            {submitting ? t.checkingShort : t.connect}
          </button>
        </form>
      </section>

      {accounts.length > 0 && <DataSection onDelete={handleDeleteAllData} />}
    </main>
  )
}

export default App
