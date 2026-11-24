import { useCallback, useEffect, useState } from 'react'
import { errorText, request } from '../api'
import { openTelegramLink } from '../telegram'
import type { Notify } from '../types'
import { Avatar, Chip, Group, Icon, Row, ScreenHeader } from '../ui'

// Админ-панель. Видна только админам из .env (сервер тоже проверяет), поэтому тексты только на русском

interface AdminEvent {
  userId: number;
  name: string;
  step: string;
  title: string;
  detail?: string | null;
  at: number;
}

interface Overview {
  days: number;
  usersTotal: number;
  usersNew: number;
  usersActiveWeek: number;
  usersBlocked: number;
  cohort: number;
  cohortConnected: number;
  paid: number;
  trials: number;
  revenue30: number;
  revenueTotal: number;
  billingEnabled: boolean;
  mailboxes: number;
  mailboxesOk: number;
  mailboxesError: number;
  mailboxesConnecting: number;
  sentDay: number;
  sentWeek: number;
  deadWeek: number;
  queued: number;
  stuck: number;
  stuckWithError: number;
  uptimeMillis: number;
  lastBackupAt?: number | null;
  events: AdminEvent[];
}

interface UserItem {
  id: number;
  name: string;
  username?: string | null;
  plan: string;
  trial: boolean;
  mailboxes: number;
  mailboxErrors: number;
  lastSeenAt: number;
  firstSeenAt?: number | null;
  blocked: boolean;
  admin: boolean;
}

interface UsersPage {
  total: number;
  counts: Record<string, number>;
  items: UserItem[];
}

interface UserCard {
  id: number;
  name: string;
  username?: string | null;
  firstSeenAt?: number | null;
  lastSeenAt?: number | null;
  blocked: boolean;
  admin: boolean;
  language: string;
  plan: string;
  source?: string | null;
  expiresAt?: number | null;
  lifetime: boolean;
  recurring: boolean;
  renewalCancelled: boolean;
  trialRecord: boolean;
  lastPayment?: { amount: number; offer: string; at: number } | null;
  startSource?: string | null;
  mailboxes: { id: number; email: string; host: string; forward: boolean; status: string; problem?: string | null; paused: boolean }[];
  funnel: AdminEvent[];
}

export type AdminSection = 'overview' | 'users';
export type UserFilter = 'all' | 'paid' | 'free' | 'nobox' | 'errors';

const PLAN_NAMES: Record<string, string> = { FREE: 'Free', PRO: 'Pro', BUSINESS: 'Business' };

const SOURCE_NAMES: Record<string, string> = {
  TRIAL: 'пробный период',
  STARS: 'оплачено звездами',
  GRANT: 'выдано админом',
  INVITE: 'по ссылке-приглашению',
  REFERRAL: 'награда за друзей',
};

// Сегодня - время, вчера - «вчера», иначе дата
function when(millis?: number | null): string {
  if (!millis) return '-';
  const date = new Date(millis);
  const now = new Date();
  const time = date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  if (date.toDateString() === now.toDateString()) return time;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (date.toDateString() === yesterday.toDateString()) return `вчера, ${time}`;
  return date.toLocaleDateString('ru-RU', {
    day: 'numeric', month: 'short', year: date.getFullYear() === now.getFullYear() ? undefined : 'numeric',
  });
}

function fullDate(millis?: number | null): string {
  if (!millis) return '-';
  return new Date(millis).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
}

// Короткая дата для строк: «7 нояб.» или «7 нояб. 2025»
function shortDate(millis?: number | null): string {
  if (!millis) return '-';
  const date = new Date(millis);
  return date.toLocaleDateString('ru-RU', {
    day: 'numeric', month: 'short', year: date.getFullYear() === new Date().getFullYear() ? undefined : 'numeric',
  });
}

function plural(n: number, one: string, few: string, many: string): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
  return many;
}

const percent = (part: number, total: number) => (total > 0 ? Math.round((part * 100) / total) : 0);

function PlanPill({ plan, trial }: { plan: string; trial?: boolean }) {
  return (
    <span className={`plan-pill plan-pill-${plan.toLowerCase()} admin-pill`}>
      {PLAN_NAMES[plan] ?? plan}{trial ? ' · проб.' : ''}
    </span>
  );
}

// Загрузка JSON админ-API: null - ошибка (текст ушел в notify)
async function load<T>(path: string, notify: Notify, method: 'GET' | 'POST' | 'DELETE' = 'GET', body?: unknown): Promise<T | null> {
  try {
    const response = await request(`/admin${path}`, method, body);
    if (!response.ok) {
      notify('error', await errorText(response, 'Админ-панель'));
      return null;
    }
    return (await response.json()) as T;
  } catch {
    notify('error', 'Нет связи с сервером');
    return null;
  }
}

/** Вкладка «Админ»: обзор и люди. */
export function AdminTab({ section, onSection, filter, onFilter, onOpenUser, notify }: {
  section: AdminSection;
  onSection: (section: AdminSection) => void;
  filter: UserFilter;
  onFilter: (filter: UserFilter) => void;
  onOpenUser: (id: number) => void;
  notify: Notify;
}) {
  return (
    <div className="screen">
      <ScreenHeader large title="Админ" />
      <div className="segmented" role="tablist">
        <button type="button" role="tab" aria-selected={section === 'overview'} onClick={() => onSection('overview')}>Обзор</button>
        <button type="button" role="tab" aria-selected={section === 'users'} onClick={() => onSection('users')}>Люди</button>
      </div>
      {section === 'overview'
        ? (
          <AdminOverview
            notify={notify}
            onOpenUser={onOpenUser}
            onUsers={(value) => { onFilter(value); onSection('users'); }}
          />
        )
        : <AdminUsers filter={filter} onFilter={onFilter} onOpenUser={onOpenUser} notify={notify} />}
    </div>
  );
}

function Tile({ label, value, note, tone, onClick }: {
  label: string;
  value: string | number;
  note?: string;
  tone?: 'ok' | 'warn' | 'error';
  onClick?: () => void;
}) {
  const content = (
    <>
      <span className="tile-label">{label}</span>
      <span className={tone ? `tile-value tone-text-${tone}` : 'tile-value'}>{value}</span>
      {note && <span className="tile-note">{note}</span>}
    </>
  );
  return onClick
    ? <button type="button" className="tile" onClick={onClick}>{content}</button>
    : <div className="tile">{content}</div>;
}

function AdminOverview({ notify, onOpenUser, onUsers }: {
  notify: Notify;
  onOpenUser: (id: number) => void;
  onUsers: (filter: UserFilter) => void;
}) {
  const [data, setData] = useState<Overview | null>(null);

  useEffect(() => {
    load<Overview>('/overview', notify).then(setData);
  }, [notify]);

  if (!data) return <p className="empty">Загружаю…</p>;

  const systemOk = data.mailboxesError === 0 && data.deadWeek === 0;
  const uptimeDays = Math.floor(data.uptimeMillis / 86_400_000);
  const uptimeHours = Math.floor((data.uptimeMillis % 86_400_000) / 3_600_000);

  return (
    <>
      <p className="admin-period">За {data.days} дней</p>
      <div className="tiles">
        <Tile label="Пользователей" value={data.usersTotal} note={`+${data.usersNew} новых`} onClick={() => onUsers('all')} />
        <Tile
          label="Подключили ящик"
          value={`${percent(data.cohortConnected, data.cohort)}%`}
          note={`${data.cohortConnected} из ${data.cohort} новых`}
        />
        <Tile label="Платных" value={data.paid} note={`${data.trials} на пробном Pro`} onClick={() => onUsers('paid')} />
        <Tile
          label="Система"
          value={systemOk ? 'Норма' : 'Ошибки'}
          tone={systemOk ? 'ok' : 'warn'}
          note={data.mailboxesError > 0
            ? `${data.mailboxesError} ${plural(data.mailboxesError, 'ящик', 'ящика', 'ящиков')} с ошибкой`
            : `${data.mailboxesOk} из ${data.mailboxes} ящиков работают`}
          onClick={data.mailboxesError > 0 ? () => onUsers('errors') : undefined}
        />
      </div>

      {data.stuck > 0 && (
        <Group>
          <Row
            icon="group"
            iconBg="#8A6A2B"
            title={`${data.stuck} ${plural(data.stuck, 'человек', 'человека', 'человек')} не ${plural(data.stuck, 'подключил', 'подключили', 'подключили')} ящик`}
            subtitle={data.stuckWithError > 0 ? `${data.stuckWithError} из них с ошибкой подключения` : 'Запустили бота, но до ящика не дошли'}
            chevron
            onClick={() => onUsers('nobox')}
          />
        </Group>
      )}

      <Group title="Письма">
        <Row title="Доставлено за сутки" value={data.sentDay} />
        <Row title="За неделю" value={data.sentWeek} />
        <Row title="Не доставлено за неделю" value={<span className={data.deadWeek > 0 ? 'tone-text-error' : undefined}>{data.deadWeek}</span>} />
        <Row title="В очереди сейчас" value={data.queued} />
      </Group>

      {data.billingEnabled && (
        <Group title="Деньги">
          <Row title="Доход за 30 дней" value={`${data.revenue30} ⭐`} />
          <Row title="Всего" value={`${data.revenueTotal} ⭐`} />
        </Group>
      )}

      {data.events.length > 0 && (
        <Group title="Последние события">
          {data.events.map((event) => (
            <Row
              key={`${event.userId}-${event.step}-${event.at}`}
              title={event.name}
              subtitle={event.detail ? `${event.title} · ${event.detail}` : event.title}
              value={when(event.at)}
              onClick={() => onOpenUser(event.userId)}
            />
          ))}
        </Group>
      )}

      <p className="group-footer">
        Без перезапуска: {uptimeDays} д {uptimeHours} ч · последний бэкап: {data.lastBackupAt ? when(data.lastBackupAt) : 'еще не было'}
        {' · '}заблокировали бота: {data.usersBlocked}
      </p>
    </>
  );
}

const FILTERS: { id: UserFilter; label: string }[] = [
  { id: 'all', label: 'Все' },
  { id: 'paid', label: 'Платные' },
  { id: 'free', label: 'Free' },
  { id: 'nobox', label: 'Без ящика' },
  { id: 'errors', label: 'С ошибкой' },
];

function AdminUsers({ filter, onFilter, onOpenUser, notify }: {
  filter: UserFilter;
  onFilter: (filter: UserFilter) => void;
  onOpenUser: (id: number) => void;
  notify: Notify;
}) {
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState<UsersPage | null>(null);

  useEffect(() => {
    const timer = setTimeout(() => setQuery(input.trim()), 350);
    return () => clearTimeout(timer);
  }, [input]);

  useEffect(() => {
    let alive = true;
    const params = new URLSearchParams({ filter, limit: '200' });
    if (query) params.set('q', query);
    load<UsersPage>(`/users?${params}`, notify).then((value) => {
      if (alive && value) setPage(value);
    });
    return () => {
      alive = false;
    };
  }, [filter, query, notify]);

  return (
    <>
      <label className="search-field">
        <Icon name="search" size={18} />
        <input
          type="search"
          placeholder="ID, @username или имя"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          aria-label="Поиск пользователя"
        />
      </label>

      <div className="chips">
        {FILTERS.map((item) => (
          <Chip key={item.id} selected={filter === item.id} onClick={() => onFilter(item.id)}>
            {item.label}{page?.counts[item.id] !== undefined ? ` ${page.counts[item.id]}` : ''}
          </Chip>
        ))}
      </div>

      {!page && <p className="empty">Загружаю…</p>}
      {page && page.items.length === 0 && <p className="empty">Никого не нашлось</p>}

      {page && page.items.length > 0 && (
        <Group footer={page.total > page.items.length ? `Показаны ${page.items.length} из ${page.total}: уточните поиск` : undefined}>
          {page.items.map((user) => {
            const boxes = user.mailboxes === 0
              ? 'ящика нет'
              : `${user.mailboxes} ${plural(user.mailboxes, 'ящик', 'ящика', 'ящиков')}`;
            const sub = [
              user.username ? `@${user.username}` : String(user.id),
              boxes,
              user.mailboxErrors > 0 ? 'ошибка ящика' : null,
              user.blocked ? 'заблокировал бота' : null,
            ].filter(Boolean).join(' · ');
            return (
              <button key={user.id} type="button" className="row mailbox-row admin-user-row" onClick={() => onOpenUser(user.id)}>
                <Avatar text={user.name} />
                <span className="row-text">
                  <span className="row-title">
                    {user.name}
                    <PlanPill plan={user.plan} trial={user.trial} />
                    {user.admin && <span className="badge">админ</span>}
                  </span>
                  <span className={user.mailboxErrors > 0 ? 'row-subtitle tone-text-error' : 'row-subtitle'}>{sub}</span>
                </span>
                <span className="row-value">{when(user.lastSeenAt)}</span>
              </button>
            );
          })}
        </Group>
      )}
    </>
  );
}

const TERMS: { id: string; label: string; words: string }[] = [
  { id: '7d', label: '7 дн.', words: 'на 7 дней' },
  { id: '1m', label: '1 мес.', words: 'на месяц' },
  { id: '3m', label: '3 мес.', words: 'на 3 месяца' },
  { id: '1y', label: '1 год', words: 'на год' },
  { id: 'forever', label: '∞', words: 'навсегда' },
  { id: 'custom', label: 'Свой', words: '' },
];

const UNITS: { id: 'd' | 'm' | 'y'; label: string }[] = [
  { id: 'd', label: 'дней' },
  { id: 'm', label: 'месяцев' },
  { id: 'y', label: 'лет' },
];

type Confirm = 'revoke' | 'refund' | 'delete' | null;

/** Карточка пользователя: подписка, ящики, путь по воронке и действия. */
export function AdminUserScreen({ userId, notify, onBack }: { userId: number; notify: Notify; onBack: () => void }) {
  const [card, setCard] = useState<UserCard | null>(null);
  const [missing, setMissing] = useState(false);
  const [plan, setPlan] = useState<'PRO' | 'BUSINESS'>('PRO');
  const [term, setTerm] = useState('1m');
  const [amount, setAmount] = useState('45');
  const [unit, setUnit] = useState<'d' | 'm' | 'y'>('d');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<Confirm>(null);

  const reload = useCallback(async () => {
    const value = await load<UserCard>(`/users/${userId}`, notify);
    if (value) setCard(value);
    else setMissing(true);
  }, [userId, notify]);

  useEffect(() => {
    reload();
  }, [reload]);

  const act = async (path: string, method: 'POST' | 'DELETE', body?: unknown, after?: () => void) => {
    setBusy(true);
    try {
      const result = await load<{ message: string; card?: UserCard | null }>(path, notify, method, body);
      if (result) {
        notify('ok', result.message);
        if (result.card) setCard(result.card);
        setConfirm(null);
        after?.();
      }
    } finally {
      setBusy(false);
    }
  };

  if (!card) {
    return (
      <div className="screen">
        <ScreenHeader title="Пользователь" onBack={onBack} />
        <p className="empty">{missing ? 'Пользователь не найден' : 'Загружаю…'}</p>
      </div>
    );
  }

  const amountNumber = Number.parseInt(amount, 10);
  const customValid = Number.isFinite(amountNumber) && amountNumber > 0 && amountNumber <= 999;
  const termSpec = term === 'custom' ? `${amountNumber}${unit}` : term;
  const unitWords = unit === 'd'
    ? plural(amountNumber, 'день', 'дня', 'дней')
    : unit === 'm' ? plural(amountNumber, 'месяц', 'месяца', 'месяцев') : plural(amountNumber, 'год', 'года', 'лет');
  const termWords = term === 'custom' ? `на ${customValid ? amountNumber : '…'} ${unitWords}` : TERMS.find((item) => item.id === term)?.words;
  const paidActive = card.plan !== 'FREE' && !card.admin && card.source !== 'TRIAL';

  const subscriptionText = card.admin
    ? 'Business навсегда'
    : card.plan === 'FREE'
      ? 'Free'
      : `${PLAN_NAMES[card.plan]} ${card.lifetime ? 'навсегда' : card.expiresAt ? `до ${fullDate(card.expiresAt)}` : ''}`;
  const subscriptionSource = card.admin ? 'админ из .env' : card.source ? SOURCE_NAMES[card.source] ?? card.source : undefined;

  return (
    <div className="screen">
      <ScreenHeader title="" onBack={onBack} />

      <div className="mailbox-hero">
        <Avatar text={card.name} size={60} />
        <div className="mailbox-hero-text">
          <h1 className="screen-title">
            {card.name} <PlanPill plan={card.plan} trial={card.source === 'TRIAL'} />
          </h1>
          <span className="screen-subtitle">
            {card.username ? `@${card.username} · ` : ''}{card.id}
            {card.blocked ? ' · заблокировал бота' : ''}
          </span>
        </div>
      </div>

      {card.username && (
        <button type="button" className="tinted big" onClick={() => openTelegramLink(`https://t.me/${card.username}`)}>
          <Icon name="send" size={18} /> Написать
        </button>
      )}

      <Group title="Подписка">
        <Row title={subscriptionText} subtitle={subscriptionSource} />
        {card.recurring && <Row title="Автопродление" value="включено" />}
        {card.renewalCancelled && <Row title="Автопродление" value="выключено пользователем" />}
        {card.lastPayment && (
          <Row title="Последняя оплата" value={`${card.lastPayment.amount} ⭐ · ${shortDate(card.lastPayment.at)}`} />
        )}
        <Row title="Пришел" value={`${card.startSource ? `${card.startSource} · ` : ''}${shortDate(card.firstSeenAt)}`} />
        <Row title="Последний визит" value={when(card.lastSeenAt)} />
      </Group>

      <Group title={`Ящики: ${card.mailboxes.length}`}>
        {card.mailboxes.length === 0 && <Row title="Ящиков нет" />}
        {card.mailboxes.map((box) => (
          <Row
            key={box.id}
            title={box.email}
            subtitle={[
              box.forward ? 'пересылка' : box.host,
              box.paused ? 'на паузе' : null,
              box.problem,
            ].filter(Boolean).join(' · ')}
            value={<span className={`tone-text-${box.status === 'OK' ? 'ok' : box.status === 'ERROR' ? 'error' : 'warn'}`}>
              {box.status === 'OK' ? 'работает' : box.status === 'ERROR' ? 'ошибка' : 'подключается'}
            </span>}
          />
        ))}
      </Group>

      {!card.admin && (
        <Group title="Выдать подписку" footer={paidActive ? 'Если такой тариф уже есть, срок добавится к текущему.' : undefined}>
          <div className="admin-grant">
            <div className="chips">
              {(['PRO', 'BUSINESS'] as const).map((value) => (
                <Chip key={value} selected={plan === value} onClick={() => setPlan(value)}>{PLAN_NAMES[value]}</Chip>
              ))}
            </div>
            <div className="chips">
              {TERMS.map((item) => (
                <Chip key={item.id} selected={term === item.id} onClick={() => setTerm(item.id)}>{item.label}</Chip>
              ))}
            </div>
            {term === 'custom' && (
              <div className="admin-custom">
                <input
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={999}
                  className="admin-amount"
                  value={amount}
                  onChange={(event) => setAmount(event.target.value)}
                  aria-label="Срок"
                />
                <div className="chips">
                  {UNITS.map((item) => (
                    <Chip key={item.id} selected={unit === item.id} onClick={() => setUnit(item.id)}>{item.label}</Chip>
                  ))}
                </div>
              </div>
            )}
            <button
              type="button"
              className="primary big"
              disabled={busy || (term === 'custom' && !customValid)}
              onClick={() => act(`/users/${card.id}/grant`, 'POST', { plan, term: termSpec })}
            >
              Выдать {PLAN_NAMES[plan]} {termWords}
            </button>
          </div>
        </Group>
      )}

      {!card.admin && (
        <Group
          footer={confirm === 'refund'
            ? `Тариф отключится сразу, автопродление отменится, Telegram вернет пользователю ${card.lastPayment?.amount ?? 0} ⭐ за последнюю оплату${card.lastPayment ? ` (от ${fullDate(card.lastPayment.at)})` : ''}. Отменить это нельзя.`
            : confirm === 'revoke'
              ? 'Тариф отключится сразу, автопродление отменится. Звезды не возвращаются.'
              : confirm === 'delete'
                ? 'Удалятся ящики, настройки и история пользователя. Действующая оплаченная подписка сохранится. Отменить это нельзя.'
                : undefined}
        >
          {confirm ? (
            <>
              <Row
                title={busy ? 'Выполняю…' : confirm === 'refund' ? 'Отозвать и вернуть' : confirm === 'revoke' ? 'Отозвать' : 'Удалить'}
                danger
                onClick={busy ? undefined : () => {
                  if (confirm === 'delete') act(`/users/${card.id}`, 'DELETE', undefined, onBack);
                  else act(`/users/${card.id}/revoke`, 'POST', { refund: confirm === 'refund' });
                }}
              />
              <Row title="Отмена" accent onClick={() => setConfirm(null)} />
            </>
          ) : (
            <>
              {card.trialRecord && (
                <Row title="Сбросить пробный период" accent onClick={busy ? undefined : () => act(`/users/${card.id}/reset-trial`, 'POST')} />
              )}
              {paidActive && <Row title="Отозвать подписку" danger onClick={() => setConfirm('revoke')} />}
              {paidActive && card.lastPayment && (
                <Row title="Отозвать подписку и вернуть оплату" danger onClick={() => setConfirm('refund')} />
              )}
              <Row title="Удалить данные пользователя" danger onClick={() => setConfirm('delete')} />
            </>
          )}
        </Group>
      )}

      {card.funnel.length > 0 && (
        <Group title="Путь">
          {card.funnel.map((event) => (
            <Row key={`${event.step}-${event.at}`} title={event.title} subtitle={event.detail ?? undefined} value={when(event.at)} />
          ))}
        </Group>
      )}
    </div>
  );
}
