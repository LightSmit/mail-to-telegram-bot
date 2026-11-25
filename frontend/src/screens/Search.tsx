import { useEffect, useRef, useState } from 'react'
import { t } from '../i18n'
import {
  formatListDate, MAIL_TIMEOUT_MS, MessageView, readError, setStar, StarButton,
  type MailItem, type Request,
} from '../MailboxView'
import { showBackButton } from '../telegram'
import type { Account } from '../types'
import { Avatar, Icon, ScreenHeader } from '../ui'

// Результат одного ящика: ищем во всех параллельно и показываем, кто ответил
interface SearchGroup {
  accountId: number;
  email: string;
  name: string;
  loading: boolean;
  uidValidity?: number | null;
  total: number;
  items: MailItem[];
  // Дальше могут быть еще совпадения / почта медленная, просмотрены не все свежие письма
  hasMore?: boolean;
  partial?: boolean;
  // Идет перебор писем: ищем совпадения по части слова
  scanning?: boolean;
  error?: string | null;
}

interface SearchPage {
  uidValidity: number;
  total: number;
  items: MailItem[];
  hasMore: boolean;
  partial?: boolean;
  nextCursor?: number | null;
  serverPhase?: boolean;
}

// Сколько писем каждого ящика показывать
const PER_MAILBOX = 15;

interface Props {
  request: Request;
  pro: boolean;
  // Ящики с входом в почту (пересылки без пароля в поиске не участвуют)
  mailboxes: Account[];
  hasForwardMailboxes: boolean;
  onUpgrade?: () => void;
  onOpenMailbox: (accountId: number, query: string) => void;
  onBack: () => void;
}

interface OpenMessage {
  accountId: number;
  uid: number;
  uidValidity: number;
}

// Поиск сразу по входящим всех ящиков
export function SearchScreen({ request, pro, mailboxes, hasForwardMailboxes, onUpgrade, onOpenMailbox, onBack }: Props) {
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [groups, setGroups] = useState<SearchGroup[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<OpenMessage | null>(null);
  const requestId = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (pro) inputRef.current?.focus();
  }, [pro]);

  // Ищем, когда пользователь перестал печатать
  useEffect(() => {
    const timer = setTimeout(() => setQuery(input.trim()), 600);
    return () => clearTimeout(timer);
  }, [input]);

  useEffect(() => showBackButton(() => (open ? setOpen(null) : onBack())), [open, onBack]);

  // Каждый ящик ищется отдельным запросом: медленный ящик не задерживает остальные
  const searchKey = mailboxes.map((account) => account.id).join(',');
  useEffect(() => {
    const id = ++requestId.current;
    setError(null);
    if (!pro || (!query && !unreadOnly)) {
      setGroups(null);
      return;
    }
    const patch = (accountId: number, value: Partial<SearchGroup>) => {
      if (id !== requestId.current) return;
      setGroups((current) => current && current.map((group) => (group.accountId === accountId ? { ...group, ...value } : group)));
    };
    setGroups(mailboxes.map((account) => ({
      accountId: account.id, email: account.email, name: account.name, loading: true, total: 0, items: [],
    })));
    const params = new URLSearchParams({ folder: 'inbox', offset: '0', limit: String(PER_MAILBOX) });
    if (query) params.set('q', query);
    if (unreadOnly) params.set('unread', 'true');
    // Шаг 1 - поиск силами почты (мгновенно), шаг 2 - перебор свежих писем по части слова
    mailboxes.forEach(async (account) => {
      const fetchPage = async (cursor?: number): Promise<SearchPage | string> => {
        const stepParams = new URLSearchParams(params);
        if (cursor) stepParams.set('cursor', String(cursor));
        const response = await request(`/accounts/${account.id}/messages?${stepParams}`, 'GET', undefined, MAIL_TIMEOUT_MS);
        if (!response.ok) return readError(response, t.loadEmailsFailed);
        return response.json();
      };
      const combine = (first: MailItem[], second: MailItem[]) => {
        const known = new Set(first.map((item) => item.uid));
        return [...first, ...second.filter((item) => !known.has(item.uid))].sort((a, b) => (b.date ?? 0) - (a.date ?? 0));
      };
      try {
        const first = await fetchPage();
        if (typeof first === 'string') {
          patch(account.id, { loading: false, error: first });
          return;
        }
        const scanNext = Boolean(first.serverPhase && first.nextCursor);
        patch(account.id, {
          loading: false, scanning: scanNext, uidValidity: first.uidValidity, total: first.items.length,
          items: first.items.slice(0, PER_MAILBOX), hasMore: first.hasMore, partial: false,
        });
        if (!scanNext) return;

        try {
          const second = await fetchPage(first.nextCursor ?? undefined);
          if (typeof second === 'string') {
            patch(account.id, { scanning: false });
            return;
          }
          const all = combine(first.items, second.items);
          // Пачка писем может дать больше совпадений, чем показываем: остальные - в ящике
          patch(account.id, {
            scanning: false, total: all.length, items: all.slice(0, PER_MAILBOX),
            hasMore: second.hasMore || all.length > PER_MAILBOX, partial: second.partial === true,
          });
        } catch {
          patch(account.id, { scanning: false, partial: true });
        }
      } catch {
        patch(account.id, { loading: false, error: t.mailServerTimeout });
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, unreadOnly, pro, searchKey]);

  const update = (accountId: number, uid: number, patch: Partial<MailItem>) =>
    setGroups((current) => current && current.map((group) => (group.accountId !== accountId ? group : {
      ...group,
      items: group.items.map((item) => (item.uid === uid ? { ...item, ...patch } : item)),
    })));

  const toggleStar = async (group: SearchGroup, item: MailItem) => {
    if (group.uidValidity == null) return;
    const value = !item.starred;
    update(group.accountId, item.uid, { starred: value });
    if (!(await setStar(request, group.accountId, 'inbox', group.uidValidity, item.uid, value))) {
      update(group.accountId, item.uid, { starred: !value });
      setError(t.starFailed);
    }
  };

  if (open) {
    return (
      <MessageView
        accountId={open.accountId}
        folder="inbox"
        uid={open.uid}
        uidValidity={open.uidValidity}
        request={request}
        onBack={() => setOpen(null)}
        onChanged={(patch) => update(open.accountId, open.uid, patch)}
      />
    );
  }

  const found = groups?.reduce((sum, group) => sum + group.total, 0) ?? 0;
  const loading = groups?.some((group) => group.loading || group.scanning) ?? false;
  const visibleGroups = groups?.filter((group) => group.loading || group.scanning || group.items.length > 0 || group.error || group.partial) ?? [];

  return (
    <div className="screen">
      <ScreenHeader title={t.searchAll} subtitle={t.searchAllLede} onBack={onBack} />

      {!pro ? (
        <div className="pro-hint search-pro">
          <span className="pro-badge">{t.proBadge}</span> {t.searchAllPro}
          {onUpgrade && <button type="button" className="link-button" onClick={onUpgrade}>{t.showPlans}</button>}
        </div>
      ) : (
        <>
          <label className="search-field">
            <Icon name="search" size={18} />
            <input
              ref={inputRef}
              type="search"
              placeholder={t.searchAllPlaceholder}
              value={input}
              onChange={(event) => setInput(event.target.value)}
              aria-label={t.searchAll}
            />
          </label>

          <div className="chips">
            <button type="button" className="chip" aria-pressed={unreadOnly} onClick={() => setUnreadOnly((value) => !value)}>
              {t.unread}
            </button>
          </div>

          {error && <div className="notice notice-error">{error}</div>}

          {!query && !unreadOnly && <p className="empty">{t.searchAllStart}</p>}
          {groups && (
            <p className="mail-count">
              {found > 0 ? t.searchAllFound(found) : loading ? t.searchAllSearching : t.searchAllEmpty}
            </p>
          )}

          {visibleGroups.map((group) => (
            <section key={group.accountId} className="group search-group">
              <h2 className="group-title search-group-title">
                <Avatar text={group.name && group.name !== group.email ? group.name : group.email} size={20} />
                {group.name && group.name !== group.email ? group.name : group.email}
              </h2>
              {group.loading ? (
                <p className="search-group-loading">{t.searchAllSearching}</p>
              ) : group.error ? (
                <div className="notice notice-error">{group.error}</div>
              ) : (
                <ul className="mail-list">
                  {group.items.map((item) => (
                    <li key={item.uid} className="mail-item">
                      <StarButton starred={item.starred === true} onClick={() => toggleStar(group, item)} />
                      <button
                        type="button"
                        className={item.seen ? 'mail-row' : 'mail-row unread'}
                        onClick={() => group.uidValidity != null && setOpen({ accountId: group.accountId, uid: item.uid, uidValidity: group.uidValidity })}
                      >
                        <span className="mail-row-top">
                          <span className="mail-from">{item.fromName || t.unknownSender}</span>
                          <span className="mail-date">{formatListDate(item.date)}</span>
                        </span>
                        <span className="mail-subject">
                          {item.hasAttachments && <span aria-label={t.hasAttachments}>📎 </span>}
                          {item.subject || t.noSubject}
                        </span>
                      </button>
                    </li>
                  ))}
                  {group.scanning && (
                    <li className="search-scanning">{t.searchAllScanning}</li>
                  )}
                  {!group.scanning && (group.hasMore || group.partial || group.total > group.items.length) && (
                    <li>
                      <button type="button" className="row row-accent search-more" onClick={() => onOpenMailbox(group.accountId, query)}>
                        <span className="row-text">
                          <span className="row-title">{t.searchAllOpen}</span>
                          {group.partial && <span className="row-subtitle">{t.searchAllPartial}</span>}
                        </span>
                        <Icon name="chevron-right" size={14} className="row-chevron" />
                      </button>
                    </li>
                  )}
                </ul>
              )}
            </section>
          ))}

          {groups && <p className="group-footer">{t.searchAllWindow}</p>}
          {hasForwardMailboxes && groups && <p className="group-footer">{t.searchAllForwardSkipped}</p>}
        </>
      )}
    </div>
  );
}
