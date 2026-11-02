import { useEffect, useMemo, useRef, useState } from 'react'
import { t } from './i18n'
import { showBackButton } from './telegram'

type Folder = 'inbox' | 'sent';
type Sort = 'new' | 'old' | 'sender';

interface MailItem {
  uid: number;
  fromName: string;
  fromAddress?: string | null;
  to: string;
  subject: string;
  date?: number | null;
  seen: boolean;
  hasAttachments: boolean;
}

interface MailPage {
  uidValidity: number;
  total: number;
  items: MailItem[];
  hasMore: boolean;
}

interface MailMessage {
  uid: number;
  from: string;
  to: string;
  cc: string;
  subject: string;
  date?: number | null;
  body?: string | null;
  attachments: string[];
  seen: boolean;
}

type Request = (
  path: string,
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE',
  body?: unknown,
  timeoutMs?: number,
) => Promise<Response>;

interface Props {
  account: { id: number; name: string; email: string };
  request: Request;
  onClose: () => void;
}

const PAGE_SIZE = 30;
// Почтовый сервер может искать долго, особенно в больших ящиках
const MAIL_TIMEOUT_MS = 65000;

async function readError(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string') return body.error;
  } catch {
    // тело не JSON
  }
  return `${fallback} (HTTP ${response.status})`;
}

// Сегодня - время, в этом году - день и месяц, иначе - полная дата
function formatListDate(millis?: number | null): string {
  if (!millis) return '';
  const date = new Date(millis);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString(t.locale, { hour: '2-digit', minute: '2-digit' });
  }
  if (date.getFullYear() === now.getFullYear()) {
    return date.toLocaleDateString(t.locale, { day: 'numeric', month: 'short' });
  }
  return date.toLocaleDateString(t.locale);
}

// Сервер ставит служебные заглушки вместо пустой темы и отправителя
const displaySubject = (subject: string) => (!subject || subject === '(no subject)' ? t.noSubject : subject);
const displaySender = (from: string) => (!from || from === '(sender unknown)' ? t.unknownSender : from);

function formatFullDate(millis?: number | null): string {
  if (!millis) return '';
  return new Date(millis).toLocaleString(t.locale, {
    day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

export function MailboxView({ account, request, onClose }: Props) {
  const [folder, setFolder] = useState<Folder>('inbox');
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [withAttachments, setWithAttachments] = useState(false);
  const [sort, setSort] = useState<Sort>('new');

  const [items, setItems] = useState<MailItem[]>([]);
  const [uidValidity, setUidValidity] = useState<number | null>(null);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openUid, setOpenUid] = useState<number | null>(null);

  // Ответ на устаревший запрос (сменили папку или поиск) не должен затирать новый
  const requestId = useRef(0);

  const load = async (offset: number) => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    const params = new URLSearchParams({ folder, offset: String(offset), limit: String(PAGE_SIZE) });
    if (query) params.set('q', query);
    if (unreadOnly) params.set('unread', 'true');

    try {
      const response = await request(`/accounts/${account.id}/messages?${params}`, 'GET', undefined, MAIL_TIMEOUT_MS);
      if (id !== requestId.current) return;
      if (!response.ok) {
        setError(await readError(response, t.loadEmailsFailed));
        return;
      }
      const page: MailPage = await response.json();
      if (id !== requestId.current) return;
      setUidValidity(page.uidValidity);
      setTotal(page.total);
      setHasMore(page.hasMore);
      setItems((current) => {
        if (offset === 0) return page.items;
        const known = new Set(current.map((item) => item.uid));
        return [...current, ...page.items.filter((item) => !known.has(item.uid))];
      });
    } catch {
      if (id === requestId.current) setError(t.mailServerTimeout);
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  };

  // Новая папка, поиск или фильтр "непрочитанные" - загружаем заново с начала
  useEffect(() => {
    setItems([]);
    setOpenUid(null);
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder, query, unreadOnly]);

  // Поиск запускается, когда пользователь перестал печатать
  useEffect(() => {
    const timer = setTimeout(() => setQuery(queryInput.trim()), 600);
    return () => clearTimeout(timer);
  }, [queryInput]);

  // Системная кнопка «Назад»: из письма - к списку, из списка - к ящикам
  useEffect(() => showBackButton(() => (openUid !== null ? setOpenUid(null) : onClose())), [openUid, onClose]);

  const visible = useMemo(() => {
    const filtered = withAttachments ? items.filter((item) => item.hasAttachments) : items;
    const sorted = [...filtered];
    if (sort === 'old') sorted.sort((a, b) => (a.date ?? 0) - (b.date ?? 0));
    else if (sort === 'sender') sorted.sort((a, b) => a.fromName.localeCompare(b.fromName, t.locale));
    else sorted.sort((a, b) => (b.date ?? 0) - (a.date ?? 0));
    return sorted;
  }, [items, withAttachments, sort]);

  const markSeenLocally = (uid: number) =>
    setItems((current) => current.map((item) => (item.uid === uid ? { ...item, seen: true } : item)));

  if (openUid !== null && uidValidity !== null) {
    return (
      <MessageView
        accountId={account.id}
        folder={folder}
        uid={openUid}
        uidValidity={uidValidity}
        request={request}
        onBack={() => setOpenUid(null)}
        onMarkedSeen={() => markSeenLocally(openUid)}
      />
    );
  }

  return (
    <div className="mail-view">
      <div className="mail-head">
        <button type="button" className="link-button back" onClick={onClose}>{t.backToMailboxes}</button>
        <div className="mail-title">
          <span className="mailbox-name">{account.name !== account.email ? account.name : account.email}</span>
          {account.name !== account.email && <span className="mailbox-address">{account.email}</span>}
        </div>
      </div>

      <div className="segmented" role="tablist">
        <button type="button" role="tab" aria-selected={folder === 'inbox'} onClick={() => setFolder('inbox')}>{t.inbox}</button>
        <button type="button" role="tab" aria-selected={folder === 'sent'} onClick={() => setFolder('sent')}>{t.sent}</button>
      </div>

      <input
        type="search"
        className="mail-search"
        placeholder={t.searchPlaceholder}
        value={queryInput}
        onChange={(e) => setQueryInput(e.target.value)}
      />

      <div className="chips">
        <button type="button" className="chip" aria-pressed={unreadOnly} onClick={() => setUnreadOnly((value) => !value)}>
          {t.unread}
        </button>
        <button type="button" className="chip" aria-pressed={withAttachments} onClick={() => setWithAttachments((value) => !value)}>
          {t.withAttachments}
        </button>
        <select className="chip-select" value={sort} onChange={(e) => setSort(e.target.value as Sort)} aria-label={t.sortLabel}>
          <option value="new">{t.sortNew}</option>
          <option value="old">{t.sortOld}</option>
          <option value="sender">{t.sortSender}</option>
        </select>
      </div>

      {query && (
        <p className="field-hint search-hint">{t.searchHint}</p>
      )}

      {error && <div className="notice notice-error">{error}</div>}

      {!error && (
        <p className="mail-count">
          {loading && items.length === 0
            ? t.loadingEmails
            : query || unreadOnly
              ? t.found(total)
              : t.inFolder(total)}
          {withAttachments && items.length > 0 && t.attachmentsAmongLoaded(visible.length)}
        </p>
      )}

      {visible.length > 0 && (
        <ul className="mail-list">
          {visible.map((item) => (
            <li key={item.uid}>
              <button type="button" className={item.seen ? 'mail-row' : 'mail-row unread'} onClick={() => setOpenUid(item.uid)}>
                <span className="mail-row-top">
                  <span className="mail-from">{folder === 'sent' ? `${t.toPrefix}${item.to || '—'}` : item.fromName || t.unknownSender}</span>
                  <span className="mail-date">{formatListDate(item.date)}</span>
                </span>
                <span className="mail-subject">
                  {item.hasAttachments && <span aria-label={t.hasAttachments}>📎 </span>}
                  {item.subject || t.noSubject}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {!loading && !error && items.length > 0 && visible.length === 0 && (
        <p className="empty">{t.noAttachmentsLoaded}</p>
      )}

      {!loading && !error && items.length === 0 && (
        <p className="empty">{query || unreadOnly ? t.nothingFound : t.emptyFolder}</p>
      )}

      {hasMore && !error && (
        <button type="button" className="secondary more" disabled={loading} onClick={() => load(items.length)}>
          {loading ? t.loading : t.showMore}
        </button>
      )}
    </div>
  );
}

interface MessageProps {
  accountId: number;
  folder: Folder;
  uid: number;
  uidValidity: number;
  request: Request;
  onBack: () => void;
  onMarkedSeen: () => void;
}

function MessageView({ accountId, folder, uid, uidValidity, request, onBack, onMarkedSeen }: MessageProps) {
  const [message, setMessage] = useState<MailMessage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const base = `/accounts/${accountId}/messages/${uid}`;
  const params = `folder=${folder}&v=${uidValidity}`;

  useEffect(() => {
    (async () => {
      try {
        const response = await request(`${base}?${params}`, 'GET', undefined, MAIL_TIMEOUT_MS);
        if (!response.ok) {
          setError(await readError(response, t.openEmailFailed));
          return;
        }
        setMessage(await response.json());
      } catch {
        setError(t.mailServerTimeout);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [uid]);

  const markRead = async () => {
    setBusy(true);
    try {
      const response = await request(`${base}/read?${params}`, 'POST');
      if (response.ok) {
        setMessage((current) => (current ? { ...current, seen: true } : current));
        onMarkedSeen();
        setStatus(t.markedRead);
      } else {
        setStatus(await readError(response, t.markReadFailed));
      }
    } catch {
      setStatus(t.noServer);
    } finally {
      setBusy(false);
    }
  };

  const sendAttachments = async () => {
    setBusy(true);
    try {
      const response = await request(`${base}/attachments?${params}`, 'POST');
      setStatus(response.ok
        ? t.sendingToChat
        : await readError(response, t.sendAttachmentsFailed));
    } catch {
      setStatus(t.noServer);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mail-view">
      <div className="mail-head">
        <button type="button" className="link-button back" onClick={onBack}>{t.backToList}</button>
      </div>

      {error && <div className="notice notice-error">{error}</div>}
      {!error && !message && <p className="mail-count">{t.openingEmail}</p>}

      {message && (
        <article className="message">
          <h2 className="message-subject">{displaySubject(message.subject)}</h2>
          <dl className="message-meta">
            <dt>{t.metaFrom}</dt><dd>{displaySender(message.from)}</dd>
            <dt>{t.metaTo}</dt><dd>{message.to || '—'}</dd>
            {message.cc && (<><dt>{t.metaCc}</dt><dd>{message.cc}</dd></>)}
            <dt>{t.metaDate}</dt><dd>{formatFullDate(message.date)}</dd>
          </dl>

          {message.attachments.length > 0 && (
            <ul className="message-attachments">
              {message.attachments.map((name, index) => (
                <li key={`${index}-${name}`}>📎 {name}</li>
              ))}
            </ul>
          )}

          <div className="message-actions">
            {folder === 'inbox' && !message.seen && (
              <button type="button" className="secondary" disabled={busy} onClick={markRead}>{t.markRead}</button>
            )}
            {folder === 'inbox' && message.attachments.length > 0 && (
              <button type="button" className="secondary" disabled={busy} onClick={sendAttachments}>
                {t.attachmentsToChat}
              </button>
            )}
          </div>
          {status && <p className="field-hint">{status}</p>}

          <div className="message-body">{message.body ?? t.noText}</div>
        </article>
      )}
    </div>
  );
}
