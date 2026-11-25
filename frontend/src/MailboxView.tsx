import { useEffect, useMemo, useRef, useState } from 'react'
import { t } from './i18n'
import { absoluteServerUrl } from './api'
import { MailHtml } from './MailHtml'
import { downloadFile, pdfPreviewSupported, shareMessage, showBackButton } from './telegram'
import { Icon } from './ui'

type Folder = 'inbox' | 'sent';
type Sort = 'new' | 'old' | 'sender';

export interface MailItem {
  uid: number;
  fromName: string;
  fromAddress?: string | null;
  to: string;
  subject: string;
  date?: number | null;
  seen: boolean;
  hasAttachments: boolean;
  // Отмечено звездочкой в приложении
  starred?: boolean;
}

interface MailPage {
  uidValidity: number;
  total: number;
  items: MailItem[];
  hasMore: boolean;
  // Free: показаны только последние письма
  limited?: boolean;
  // Поиск идет пачками: продолжить с курсора; сколько писем просмотрено из скольких
  nextCursor?: number | null;
  scanned?: number;
  folderSize?: number;
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
  starred?: boolean;
  // Очищенный HTML письма (вид с оформлением)
  html?: string | null;
}

// Вложение, готовое к скачиванию: ссылка живет 15 минут
interface MailFile {
  index: number;
  name: string;
  type: string;
  size: number;
  url: string;
}

function previewKind(file: MailFile): 'image' | 'pdf' | null {
  const type = file.type.toLowerCase();
  if (/^image\/(png|jpe?g|gif|webp|bmp|heic|heif)/.test(type) || /\.(png|jpe?g|gif|webp|bmp)$/i.test(file.name)) return 'image';
  if ((type.startsWith('application/pdf') || /\.pdf$/i.test(file.name)) && pdfPreviewSupported) return 'pdf';
  return null;
}

function fileIcon(name: string): string {
  if (/\.(png|jpe?g|gif|webp|bmp|heic|heif)$/i.test(name)) return '🖼';
  if (/\.pdf$/i.test(name)) return '📄';
  return '📎';
}

/** Просмотр картинки или PDF поверх письма, с кнопками скачать и поделиться. */
function FileViewer({ file, kind, onClose, onDownload, onShare }: {
  file: MailFile;
  kind: 'image' | 'pdf';
  onClose: () => void;
  onDownload: () => void;
  onShare: () => void;
}) {
  const src = `${absoluteServerUrl(file.url)}?inline=1`;
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="file-viewer" role="dialog" aria-modal="true" aria-label={file.name}>
      <div className="file-viewer-bar">
        <button type="button" className="icon-button" aria-label={t.close} onClick={onClose}><Icon name="close" size={22} /></button>
        <span className="file-viewer-name">{file.name}</span>
        <button type="button" className="icon-button" aria-label={t.download} onClick={onDownload}><Icon name="download" size={22} /></button>
        <button type="button" className="icon-button" aria-label={t.share} onClick={onShare}><Icon name="share" size={22} /></button>
      </div>
      <div className="file-viewer-body">
        {failed && <p className="empty">{t.previewFailed}</p>}
        {!failed && kind === 'image' && <img src={src} alt={file.name} onError={() => setFailed(true)} />}
        {!failed && kind === 'pdf' && <iframe src={src} title={file.name} />}
      </div>
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export type Request = (
  path: string,
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE',
  body?: unknown,
  timeoutMs?: number,
) => Promise<Response>;

interface Props {
  account: { id: number; name: string; email: string };
  request: Request;
  // Тариф Free: только последние входящие, без поиска и "Отправленных"
  limited?: boolean;
  onUpgrade?: () => void;
  onClose: () => void;
  // Открыть сразу с поиском (переход из поиска по всем ящикам)
  initialQuery?: string;
}

const PAGE_SIZE = 30;
const FREE_PAGE_SIZE = 20;
// Почтовый сервер может искать долго, особенно в больших ящиках
export const MAIL_TIMEOUT_MS = 65000;

export async function readError(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string') return body.error;
  } catch {
    // тело не JSON
  }
  return `${fallback} (HTTP ${response.status})`;
}

// Сегодня - время, в этом году - день и месяц, иначе - полная дата
export function formatListDate(millis?: number | null): string {
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

/** Поставить или снять звездочку. true - получилось. */
export async function setStar(request: Request, accountId: number, folder: Folder, uidValidity: number, uid: number, value: boolean): Promise<boolean> {
  try {
    const response = await request(
      `/accounts/${accountId}/messages/${uid}/star?folder=${folder}&v=${uidValidity}`,
      value ? 'POST' : 'DELETE',
    );
    return response.ok;
  } catch {
    return false;
  }
}

export function StarButton({ starred, onClick, disabled }: { starred: boolean; onClick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      className={starred ? 'star-button on' : 'star-button'}
      aria-pressed={starred}
      aria-label={starred ? t.starRemove : t.starAdd}
      title={starred ? t.starRemove : t.starAdd}
      disabled={disabled}
      onClick={onClick}
    >
      {starred ? '★' : '☆'}
    </button>
  );
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

export function MailboxView({ account, request, limited = false, onUpgrade, onClose, initialQuery = '' }: Props) {
  const [folder, setFolder] = useState<Folder>('inbox');
  const [queryInput, setQueryInput] = useState(limited ? '' : initialQuery);
  const [query, setQuery] = useState(limited ? '' : initialQuery);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [starredOnly, setStarredOnly] = useState(false);
  const [starError, setStarError] = useState<string | null>(null);
  const [withAttachments, setWithAttachments] = useState(false);
  const [sort, setSort] = useState<Sort>('new');

  const [items, setItems] = useState<MailItem[]>([]);
  const [uidValidity, setUidValidity] = useState<number | null>(null);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openUid, setOpenUid] = useState<number | null>(null);
  const [pageLimited, setPageLimited] = useState(false);
  // Поиск: откуда продолжить и сколько писем просмотрено
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [scan, setScan] = useState<{ scanned: number; size: number } | null>(null);

  // Ответ на устаревший запрос (сменили папку или поиск) не должен затирать новый
  const requestId = useRef(0);
  // Уже показанные письма: ответ дописывается к ним (setItems применяется не сразу)
  const shown = useRef<MailItem[]>([]);

  // cursor - продолжение поиска. Пока найдено меньше страницы, поиск продолжается сам
  const load = async (offset: number, cursor?: number) => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    const params = new URLSearchParams({ folder, offset: String(offset), limit: String(limited ? FREE_PAGE_SIZE : PAGE_SIZE) });
    if (query) params.set('q', query);
    if (query && cursor) params.set('cursor', String(cursor));
    if (unreadOnly) params.set('unread', 'true');
    if (starredOnly) params.set('starred', 'true');

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
      setHasMore(page.hasMore);
      setPageLimited(page.limited === true);
      setNextCursor(page.nextCursor ?? null);
      setScan(query && page.folderSize ? { scanned: page.scanned ?? 0, size: page.folderSize } : null);
      const current = offset === 0 && !cursor ? [] : shown.current;
      const known = new Set(current.map((item) => item.uid));
      const merged = [...current, ...page.items.filter((item) => !known.has(item.uid))];
      shown.current = merged;
      setItems(merged);
      if (query) {
        // В поиске сервер не знает общее число: считаем найденное
        setTotal(merged.length);
        // Найдено меньше страницы, а письма еще есть - ищем дальше сами
        if (page.nextCursor && merged.length < offset + PAGE_SIZE) {
          void load(offset, page.nextCursor);
          return;
        }
      } else {
        setTotal(page.total);
      }
    } catch {
      if (id === requestId.current) setError(t.mailServerTimeout);
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  };

  // Новая папка, поиск или фильтр "непрочитанные" - загружаем заново с начала
  useEffect(() => {
    setItems([]);
    shown.current = [];
    setScan(null);
    setNextCursor(null);
    setOpenUid(null);
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder, query, unreadOnly, starredOnly]);

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

  const updateLocally = (uid: number, patch: Partial<MailItem>) => {
    const next = (list: MailItem[]) => list.map((item) => (item.uid === uid ? { ...item, ...patch } : item));
    shown.current = next(shown.current);
    setItems(next);
  };

  // Звездочка из списка: сразу меняем на экране, при ошибке возвращаем
  const toggleStar = async (item: MailItem) => {
    if (uidValidity === null) return;
    const value = !item.starred;
    setStarError(null);
    updateLocally(item.uid, { starred: value });
    if (!(await setStar(request, account.id, folder, uidValidity, item.uid, value))) {
      updateLocally(item.uid, { starred: !value });
      setStarError(t.starFailed);
    }
  };

  if (openUid !== null && uidValidity !== null) {
    return (
      <MessageView
        accountId={account.id}
        folder={folder}
        uid={openUid}
        uidValidity={uidValidity}
        request={request}
        onBack={() => setOpenUid(null)}
        onChanged={(patch) => updateLocally(openUid, patch)}
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
        <button type="button" role="tab" aria-selected={folder === 'sent'} disabled={limited} onClick={() => setFolder('sent')}>
          {t.sent}{limited && <span className="pro-badge">{t.proBadge}</span>}
        </button>
      </div>

      <input
        type="search"
        className="mail-search"
        placeholder={limited ? t.proSearchPlaceholder : t.searchPlaceholder}
        disabled={limited}
        value={queryInput}
        onChange={(e) => setQueryInput(e.target.value)}
      />

      <div className="chips">
        <button type="button" className="chip" aria-pressed={unreadOnly} disabled={limited} onClick={() => setUnreadOnly((value) => !value)}>
          {t.unread}{limited && <span className="pro-badge">{t.proBadge}</span>}
        </button>
        <button type="button" className="chip" aria-pressed={starredOnly} disabled={limited} onClick={() => setStarredOnly((value) => !value)}>
          {t.starredChip}{limited && <span className="pro-badge">{t.proBadge}</span>}
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
        <p className="field-hint search-hint">
          {t.searchHint}
          {scan && ` ${t.searchScanned(scan.scanned, scan.size)}`}
        </p>
      )}

      {starredOnly && <p className="field-hint search-hint">{t.starredHint}</p>}

      {error && <div className="notice notice-error">{error}</div>}
      {starError && <div className="notice notice-error">{starError}</div>}

      {!error && (
        <p className="mail-count">
          {loading && items.length === 0
            ? (query ? t.searching : t.loadingEmails)
            : starredOnly
              ? t.starredCount(total)
              : query || unreadOnly
                ? t.found(total)
                : t.inFolder(total)}
          {withAttachments && items.length > 0 && t.attachmentsAmongLoaded(visible.length)}
        </p>
      )}

      {visible.length > 0 && (
        <ul className="mail-list">
          {visible.map((item) => (
            <li key={item.uid} className="mail-item">
              <StarButton starred={item.starred === true} onClick={() => toggleStar(item)} />
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
        <p className="empty">{starredOnly ? t.starredEmpty : query || unreadOnly ? t.nothingFound : t.emptyFolder}</p>
      )}

      {limited && !error && (pageLimited || items.length === 0) && (
        <p className="pro-hint">
          <span className="pro-badge">{t.proBadge}</span> {t.proBrowseHint}
          {onUpgrade && (
            <button type="button" className="link-button" onClick={onUpgrade}>{t.showPlans}</button>
          )}
        </p>
      )}

      {query && loading && items.length > 0 && <p className="mail-count">{t.searching}</p>}

      {query && loading && (
        // Остановить автопродолжение поиска: ответы старых запросов игнорируются
        <button type="button" className="link-button more" onClick={() => { requestId.current++; setLoading(false); }}>
          {t.stopSearch}
        </button>
      )}

      {hasMore && !error && !(query && loading) && (
        <button
          type="button"
          className="secondary more"
          disabled={loading}
          onClick={() => (query && nextCursor ? load(items.length, nextCursor) : load(items.length))}
        >
          {loading ? t.loading : query ? t.searchFurther : t.showMore}
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
  // Письмо прочитано или снова непрочитано, звездочка поставлена или снята
  onChanged: (patch: Partial<MailItem>) => void;
  // Ящик-пересылка: входа в почту нет, «прочитано» недоступно
  forward?: boolean;
}

export function MessageView({ accountId, folder, uid, uidValidity, request, onBack, onChanged, forward = false }: MessageProps) {
  const [message, setMessage] = useState<MailMessage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Вид письма: с оформлением (если есть HTML) или простым текстом
  const [plain, setPlain] = useState(false);
  // Вложения со ссылками: загружаются при первом скачивании (бот достает их из почты)
  const [files, setFiles] = useState<MailFile[] | null>(null);
  const [fileBusy, setFileBusy] = useState<number | null>(null);

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

  const markRead = async (seen: boolean) => {
    setBusy(true);
    try {
      const response = await request(`${base}/${seen ? 'read' : 'unread'}?${params}`, 'POST');
      if (response.ok) {
        setMessage((current) => (current ? { ...current, seen } : current));
        onChanged({ seen });
        setStatus(seen ? t.markedRead : t.markedUnread);
      } else {
        setStatus(await readError(response, seen ? t.markReadFailed : t.markUnreadFailed));
      }
    } catch {
      setStatus(t.noServer);
    } finally {
      setBusy(false);
    }
  };

  const toggleStar = async () => {
    if (!message) return;
    const value = !message.starred;
    setBusy(true);
    const ok = await setStar(request, accountId, folder, uidValidity, uid, value);
    setBusy(false);
    if (ok) {
      setMessage((current) => (current ? { ...current, starred: value } : current));
      onChanged({ starred: value });
      setStatus(null);
    } else {
      setStatus(t.starFailed);
    }
  };

  // Ссылки на вложения: каждый раз свежие, старые живут 15 минут
  const loadFiles = async (): Promise<MailFile[] | null> => {
    try {
      const response = await request(`${base}/files?v=${uidValidity}`, 'GET', undefined, MAIL_TIMEOUT_MS);
      if (!response.ok) {
        setStatus(await readError(response, t.filesFailed));
        return null;
      }
      const result: { items: MailFile[]; skipped: string[] } = await response.json();
      setFiles(result.items);
      if (result.skipped.length > 0) setStatus(t.filesSkipped(result.skipped.join(', ')));
      return result.items;
    } catch {
      setStatus(t.mailServerTimeout);
      return null;
    }
  };

  // Номер в списке имен и номер у сервера расходятся, если бот пропустил большое вложение: ищем по имени
  const withFile = async (index: number, name: string, action: (file: MailFile) => Promise<void>) => {
    setFileBusy(index);
    setStatus(null);
    try {
      const list = await loadFiles();
      const file = list?.find((item) => item.name === name) ?? list?.find((item) => item.index === index);
      if (file) await action(file);
      else if (list) setStatus(t.filesFailed);
    } finally {
      setFileBusy(null);
    }
  };

  const download = (index: number, name: string) => withFile(index, name, async (file) => {
    // Штатное окно сохранения Telegram; клиент без него получает файл в чат с ботом
    if (downloadFile(absoluteServerUrl(file.url), file.name)) return;
    await sendAttachments();
    setStatus(t.downloadFallback);
  });

  // Сначала выбор чата Telegram; старый клиент - системное «Поделиться», затем чат с ботом
  const share = (index: number, name: string) => withFile(index, name, async (file) => {
    try {
      const response = await request(`${base}/files/${file.index}/share?v=${uidValidity}`, 'POST', undefined, MAIL_TIMEOUT_MS);
      if (response.ok) {
        const { id } = await response.json() as { id: string };
        const result = await shareMessage(id);
        if (result !== 'unsupported') return;
      }
    } catch {
      // ниже запасные пути
    }
    const navigatorShare = navigator as Navigator & { canShare?: (data: ShareData) => boolean };
    try {
      const response = await fetch(absoluteServerUrl(file.url));
      if (!response.ok) throw new Error(String(response.status));
      const blob = await response.blob();
      const shared = new File([blob], file.name, { type: file.type || blob.type });
      if (typeof navigator.share === 'function' && navigatorShare.canShare?.({ files: [shared] })) {
        await navigator.share({ files: [shared], title: file.name });
        return;
      }
    } catch (failure) {
      // Пользователь закрыл окно «Поделиться» - это не ошибка
      if (failure instanceof DOMException && failure.name === 'AbortError') return;
    }
    // Нет системного «Поделиться»: файл придет в чат, оттуда его можно переслать
    await sendAttachments();
    setStatus(t.shareFallback);
  });

  // Картинки и PDF смотрим прямо в приложении, остальное скачиваем
  const [viewer, setViewer] = useState<{ file: MailFile; kind: 'image' | 'pdf' } | null>(null);
  const preview = (index: number, name: string) => withFile(index, name, async (file) => {
    const kind = previewKind(file);
    if (kind) setViewer({ file, kind });
    else if (!downloadFile(absoluteServerUrl(file.url), file.name)) {
      await sendAttachments();
      setStatus(t.downloadFallback);
    }
  });

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
          <div className="message-subject-row">
            <h2 className="message-subject">{displaySubject(message.subject)}</h2>
            <StarButton starred={message.starred === true} disabled={busy} onClick={toggleStar} />
          </div>
          <dl className="message-meta">
            <dt>{t.metaFrom}</dt><dd>{displaySender(message.from)}</dd>
            <dt>{t.metaTo}</dt><dd>{message.to || '—'}</dd>
            {message.cc && (<><dt>{t.metaCc}</dt><dd>{message.cc}</dd></>)}
            <dt>{t.metaDate}</dt><dd>{formatFullDate(message.date)}</dd>
          </dl>

          {message.attachments.length > 0 && folder === 'inbox' && (
            <ul className="message-files">
              {message.attachments.map((name, index) => (
                <li key={`${index}-${name}`} className="message-file">
                  <button
                    type="button"
                    className="message-file-name"
                    disabled={fileBusy !== null}
                    onClick={() => preview(index, name)}
                  >
                    {fileIcon(name)} {name}
                    {files?.find((file) => file.name === name) && (
                      <span className="message-file-size">{formatSize(files.find((file) => file.name === name)?.size ?? 0)}</span>
                    )}
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    disabled={fileBusy !== null}
                    aria-label={t.download}
                    title={t.download}
                    onClick={() => download(index, name)}
                  >
                    {fileBusy === index ? '…' : <Icon name="download" size={20} />}
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    disabled={fileBusy !== null}
                    aria-label={t.share}
                    title={t.share}
                    onClick={() => share(index, name)}
                  >
                    <Icon name="share" size={20} />
                  </button>
                </li>
              ))}
            </ul>
          )}
          {message.attachments.length > 0 && folder !== 'inbox' && (
            <ul className="message-attachments">
              {message.attachments.map((name, index) => (
                <li key={`${index}-${name}`}>📎 {name}</li>
              ))}
            </ul>
          )}

          <div className="message-actions">
            {folder === 'inbox' && !forward && (
              <button type="button" className="secondary" disabled={busy} onClick={() => markRead(!message.seen)}>
                {message.seen ? t.markUnread : t.markRead}
              </button>
            )}
            {folder === 'inbox' && message.attachments.length > 0 && (
              <button type="button" className="secondary" disabled={busy} onClick={sendAttachments}>
                {t.attachmentsToChat}
              </button>
            )}
          </div>
          {status && <p className="field-hint">{status}</p>}

          {viewer && (
            <FileViewer
              file={viewer.file}
              kind={viewer.kind}
              onClose={() => setViewer(null)}
              onDownload={() => download(viewer.file.index, viewer.file.name)}
              onShare={() => share(viewer.file.index, viewer.file.name)}
            />
          )}

          {message.html && (
            <div className="segmented message-view-mode" role="tablist">
              <button type="button" role="tab" aria-selected={!plain} onClick={() => setPlain(false)}>{t.viewDesigned}</button>
              <button type="button" role="tab" aria-selected={plain} onClick={() => setPlain(true)}>{t.viewPlain}</button>
            </div>
          )}
          {message.html && !plain
            ? <MailHtml html={message.html} />
            : <div className="message-body">{message.body ?? t.noText}</div>}
        </article>
      )}
    </div>
  );
}
