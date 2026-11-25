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

export type AdminSection = 'overview' | 'users' | 'funnel' | 'system' | 'money';
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

const SECTIONS: { id: AdminSection; label: string }[] = [
  { id: 'overview', label: 'Обзор' },
  { id: 'users', label: 'Люди' },
  { id: 'funnel', label: 'Воронка' },
  { id: 'system', label: 'Система' },
  { id: 'money', label: 'Деньги' },
];

/** Вкладка «Админ»: обзор, люди, воронка, система, деньги. */
export function AdminTab({ section, onSection, filter, onFilter, onOpenUser, onBroadcast, notify }: {
  section: AdminSection;
  onSection: (section: AdminSection) => void;
  filter: UserFilter;
  onFilter: (filter: UserFilter) => void;
  onOpenUser: (id: number) => void;
  onBroadcast: () => void;
  notify: Notify;
}) {
  const content = (() => {
    switch (section) {
      case 'overview':
        return (
          <AdminOverview
            notify={notify}
            onOpenUser={onOpenUser}
            onUsers={(value) => { onFilter(value); onSection('users'); }}
            onBroadcast={onBroadcast}
          />
        );
      case 'users':
        return <AdminUsers filter={filter} onFilter={onFilter} onOpenUser={onOpenUser} notify={notify} />;
      case 'funnel':
        return <AdminFunnel notify={notify} onOpenUser={onOpenUser} />;
      case 'system':
        return <AdminSystem notify={notify} onOpenUser={onOpenUser} />;
      case 'money':
        return <AdminMoney notify={notify} onOpenUser={onOpenUser} />;
    }
  })();
  return (
    <div className="screen">
      <ScreenHeader large title="Админ" />
      <div className="segmented admin-sections" role="tablist">
        {SECTIONS.map((item) => (
          <button key={item.id} type="button" role="tab" aria-selected={section === item.id} onClick={() => onSection(item.id)}>
            {item.label}
          </button>
        ))}
      </div>
      {content}
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

function AdminOverview({ notify, onOpenUser, onUsers, onBroadcast }: {
  notify: Notify;
  onOpenUser: (id: number) => void;
  onUsers: (filter: UserFilter) => void;
  onBroadcast: () => void;
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

      <Group>
        <Row icon="send" iconBg="#2F6FB5" title="Рассылка" subtitle="Сообщение всем или части пользователей" chevron onClick={onBroadcast} />
      </Group>

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

/** Выбор срока: готовые варианты или свой (число дней, месяцев или лет). */
function useTerm(onChange?: () => void) {
  const [term, setTerm] = useState('1m');
  const [amount, setAmount] = useState('45');
  const [unit, setUnit] = useState<'d' | 'm' | 'y'>('d');

  const amountNumber = Number.parseInt(amount, 10);
  const valid = term !== 'custom' || (Number.isFinite(amountNumber) && amountNumber > 0 && amountNumber <= 999);
  const spec = term === 'custom' ? `${amountNumber}${unit}` : term;
  const unitWords = unit === 'd'
    ? plural(amountNumber, 'день', 'дня', 'дней')
    : unit === 'm' ? plural(amountNumber, 'месяц', 'месяца', 'месяцев') : plural(amountNumber, 'год', 'года', 'лет');
  const words = term === 'custom'
    ? `на ${valid ? amountNumber : '…'} ${unitWords}`
    : TERMS.find((item) => item.id === term)?.words ?? '';

  const change = (update: () => void) => {
    update();
    onChange?.();
  };

  const picker = (
    <>
      <div className="chips">
        {TERMS.map((item) => (
          <Chip key={item.id} selected={term === item.id} onClick={() => change(() => setTerm(item.id))}>{item.label}</Chip>
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
            onChange={(event) => change(() => setAmount(event.target.value))}
            aria-label="Срок"
          />
          <div className="chips">
            {UNITS.map((item) => (
              <Chip key={item.id} selected={unit === item.id} onClick={() => change(() => setUnit(item.id))}>{item.label}</Chip>
            ))}
          </div>
        </div>
      )}
    </>
  );

  return { spec, words, valid, picker };
}

/** Карточка пользователя: подписка, ящики, путь по воронке и действия. */
export function AdminUserScreen({ userId, notify, onBack }: { userId: number; notify: Notify; onBack: () => void }) {
  const [card, setCard] = useState<UserCard | null>(null);
  const [missing, setMissing] = useState(false);
  const [plan, setPlan] = useState<'PRO' | 'BUSINESS'>('PRO');
  const grantTerm = useTerm();
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
            {grantTerm.picker}
            <button
              type="button"
              className="primary big"
              disabled={busy || !grantTerm.valid}
              onClick={() => act(`/users/${card.id}/grant`, 'POST', { plan, term: grantTerm.spec })}
            >
              Выдать {PLAN_NAMES[plan]} {grantTerm.words}
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

// --- Воронка ---

interface FunnelData {
  days: number;
  source?: string | null;
  users: number;
  steps: { key: string; title: string; count: number }[];
  sources: Record<string, number>;
  providers: Record<string, number>;
  errors: Record<string, number>;
  stuck: { userId: number; name: string; startedAt: number; source?: string | null; lastStep: string; error?: string | null }[];
}

const ERROR_NAMES: Record<string, string> = {
  auth: 'неверный пароль',
  host: 'неверный сервер',
  connect: 'нет соединения',
  timeout: 'сервер не ответил',
  limit: 'лимит ящиков',
  input: 'не заполнены поля',
  forward_input: 'пересылка: неверный адрес',
  forward_limit: 'пересылка: лимит ящиков',
};

function CountsGroup({ title, counts, names }: { title: string; counts: Record<string, number>; names?: Record<string, string> }) {
  const entries = Object.entries(counts);
  if (entries.length === 0) return null;
  return (
    <Group title={title}>
      {entries.map(([key, count]) => <Row key={key} title={names?.[key] ?? key} value={count} />)}
    </Group>
  );
}

function AdminFunnel({ notify, onOpenUser }: { notify: Notify; onOpenUser: (id: number) => void }) {
  const [days, setDays] = useState(30);
  const [source, setSource] = useState<string | null>(null);
  const [data, setData] = useState<FunnelData | null>(null);
  // Источники за период без фильтра: чтобы чипы не пропадали при выборе
  const [sources, setSources] = useState<Record<string, number>>({});

  useEffect(() => {
    let alive = true;
    const params = new URLSearchParams({ days: String(days) });
    if (source) params.set('source', source);
    load<FunnelData>(`/funnel?${params}`, notify).then((value) => {
      if (!alive || !value) return;
      setData(value);
      if (!source) setSources(value.sources);
    });
    return () => {
      alive = false;
    };
  }, [days, source, notify]);

  const users = data?.users ?? 0;

  return (
    <>
      <div className="chips">
        {[7, 30, 90].map((value) => (
          <Chip key={value} selected={days === value} onClick={() => setDays(value)}>{value} дней</Chip>
        ))}
      </div>
      {Object.keys(sources).length > 1 && (
        <div className="chips">
          <Chip selected={source === null} onClick={() => setSource(null)}>Все источники</Chip>
          {Object.entries(sources).map(([key, count]) => (
            <Chip key={key} selected={source === key} onClick={() => setSource(key)}>{key} {count}</Chip>
          ))}
        </div>
      )}

      {!data && <p className="empty">Загружаю…</p>}
      {data && users === 0 && <p className="empty">За этот период новых пользователей нет</p>}

      {data && users > 0 && (
        <>
          <Group title={`Новые за ${data.days} дней: ${users}`} footer="Считаются только новые пользователи. Каждый шаг - сколько человек до него дошли.">
            {data.steps.filter((step) => step.key !== 'connect_error' || step.count > 0).map((step) => {
              const share = percent(step.count, users);
              return (
                <div key={step.key} className="row funnel-row">
                  <span className="row-text">
                    <span className="row-title">{step.title}</span>
                    <span className="funnel-bar" aria-hidden="true">
                      <span
                        className={step.key === 'connect_error' ? 'funnel-fill funnel-fill-error' : 'funnel-fill'}
                        style={{ width: `${Math.max(share, step.count > 0 ? 2 : 0)}%` }}
                      />
                    </span>
                  </span>
                  <span className="row-value">{step.count} · {share}%</span>
                </div>
              );
            })}
          </Group>
          <CountsGroup title="Какую почту выбирали" counts={data.providers} />
          <CountsGroup title="Ошибки подключения" counts={data.errors} names={ERROR_NAMES} />
          {data.stuck.length > 0 && (
            <Group title={`Не подключили ящик: ${data.stuck.length}`} footer="Запустили бота больше часа назад. Нажмите, чтобы открыть карточку.">
              {data.stuck.map((user) => (
                <Row
                  key={user.userId}
                  title={user.name}
                  subtitle={[user.lastStep, user.error ? ERROR_NAMES[user.error] ?? user.error : null, user.source].filter(Boolean).join(' · ')}
                  value={when(user.startedAt)}
                  onClick={() => onOpenUser(user.userId)}
                />
              ))}
            </Group>
          )}
        </>
      )}
    </>
  );
}

// --- Система ---

interface SystemData {
  mailboxes: number;
  mailboxesOk: number;
  mailboxesError: number;
  mailboxesConnecting: number;
  forwardMailboxes: number;
  broken: { accountId: number; ownerId: number; ownerName: string; email: string; host: string; problem?: string | null }[];
  hosts: Record<string, number>;
  sentDay: number;
  sentWeek: number;
  deadWeek: number;
  queued: number;
  uptimeMillis: number;
  lastBackupAt?: number | null;
  lastBackupBytes?: number | null;
  usersBlocked: number;
}

function AdminSystem({ notify, onOpenUser }: { notify: Notify; onOpenUser: (id: number) => void }) {
  const [data, setData] = useState<SystemData | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(() => load<SystemData>('/system', notify).then((value) => value && setData(value)), [notify]);

  useEffect(() => {
    reload();
  }, [reload]);

  const backup = async () => {
    setBusy(true);
    notify('info', 'Делаю бэкап…');
    const result = await load<{ message: string }>('/backup', notify, 'POST');
    setBusy(false);
    if (result) {
      notify('ok', result.message);
      reload();
    }
  };

  if (!data) return <p className="empty">Загружаю…</p>;

  const uptimeDays = Math.floor(data.uptimeMillis / 86_400_000);
  const uptimeHours = Math.floor((data.uptimeMillis % 86_400_000) / 3_600_000);

  return (
    <>
      <div className="tiles tiles-3">
        <Tile label="Работают" value={data.mailboxesOk} tone="ok" />
        <Tile label="С ошибкой" value={data.mailboxesError} tone={data.mailboxesError > 0 ? 'error' : undefined} />
        <Tile label="Ожидают" value={data.mailboxesConnecting} tone={data.mailboxesConnecting > 0 ? 'warn' : undefined} />
      </div>

      {data.broken.length > 0 && (
        <Group title="Ящики с ошибкой" footer="Владельцу бот уже написал. Нажмите, чтобы открыть его карточку.">
          {data.broken.map((box) => (
            <Row
              key={box.accountId}
              title={box.email}
              subtitle={[box.ownerName, box.problem].filter(Boolean).join(' · ')}
              chevron
              onClick={() => onOpenUser(box.ownerId)}
            />
          ))}
        </Group>
      )}

      <Group title="Доставка писем">
        <Row title="За сутки" value={data.sentDay} />
        <Row title="За неделю" value={data.sentWeek} />
        <Row title="Не доставлено за неделю" value={<span className={data.deadWeek > 0 ? 'tone-text-error' : undefined}>{data.deadWeek}</span>} />
        <Row title="В очереди" value={data.queued} />
        <Row title="Заблокировали бота" value={data.usersBlocked} />
      </Group>

      <CountsGroup title={`Почтовые серверы: ${data.mailboxes} ящиков`} counts={data.hosts} />

      <Group title="Сервер" footer="Бэкап базы приходит файлом в чат с ботом.">
        <Row title="Без перезапуска" value={`${uptimeDays} д ${uptimeHours} ч`} />
        <Row
          title="Последний бэкап"
          subtitle={data.lastBackupBytes ? `${Math.round(data.lastBackupBytes / 1024)} КБ` : undefined}
          value={data.lastBackupAt ? when(data.lastBackupAt) : 'еще не было'}
        />
        <Row title={busy ? 'Делаю бэкап…' : 'Сделать бэкап сейчас'} accent onClick={busy ? undefined : backup} />
      </Group>
    </>
  );
}

// --- Деньги ---

interface MoneyData {
  billingEnabled: boolean;
  revenue30: number;
  revenueTotal: number;
  pro: number;
  business: number;
  trials: number;
  lifetime: number;
  subscriptions: { userId: number; name: string; plan: string; source: string; expiresAt?: number | null; recurring: boolean }[];
  payments: { userId: number; name: string; amount: number; offer: string; at: number; refunded: boolean }[];
}

const OFFER_NAMES: Record<string, string> = {
  PRO_MONTH: 'Pro, месяц',
  PRO_YEAR: 'Pro, год',
  BUSINESS_MONTH: 'Business, месяц',
  BUSINESS_YEAR: 'Business, год',
};

function AdminMoney({ notify, onOpenUser }: { notify: Notify; onOpenUser: (id: number) => void }) {
  const [data, setData] = useState<MoneyData | null>(null);
  const [plan, setPlan] = useState<'PRO' | 'BUSINESS'>('PRO');
  const [link, setLink] = useState<string | null>(null);
  // Сменили срок - старая ссылка уже не про него
  const inviteTerm = useTerm(() => setLink(null));
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    load<MoneyData>('/money', notify).then((value) => value && setData(value));
  }, [notify]);

  const createInvite = async () => {
    setBusy(true);
    const result = await load<{ message: string; link?: string | null }>('/invite', notify, 'POST', { plan, term: inviteTerm.spec });
    setBusy(false);
    if (!result) return;
    if (result.link) {
      setLink(result.link);
      notify('ok', result.message);
    } else {
      notify('error', result.message);
    }
  };

  const copy = async () => {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link);
      notify('ok', 'Ссылка скопирована');
    } catch {
      notify('info', link);
    }
  };

  if (!data) return <p className="empty">Загружаю…</p>;

  return (
    <>
      {!data.billingEnabled && <p className="field-hint">Оплата выключена (BILLING_ENABLED=false): все пользуются Pro бесплатно.</p>}
      <div className="tiles">
        <Tile label="За 30 дней" value={`${data.revenue30} ⭐`} note={`всего ${data.revenueTotal} ⭐`} />
        <Tile label="Платных" value={data.pro + data.business} note={`Pro ${data.pro} · Business ${data.business}`} />
      </div>

      <Group title="Ссылка-приглашение" footer="Одноразовая: кто первым откроет и нажмет «Начать», получит подписку. Если подписка уже есть, срок добавится.">
        <div className="admin-grant">
          <div className="chips">
            {(['PRO', 'BUSINESS'] as const).map((value) => (
              <Chip key={value} selected={plan === value} onClick={() => { setPlan(value); setLink(null); }}>{PLAN_NAMES[value]}</Chip>
            ))}
          </div>
          {inviteTerm.picker}
          {link ? (
            <button type="button" className="tinted big admin-link" onClick={copy}>
              <Icon name="copy" size={18} /> <span className="admin-link-text">{link}</span>
            </button>
          ) : (
            <button type="button" className="primary big" disabled={busy || !inviteTerm.valid} onClick={createInvite}>
              Создать ссылку: {PLAN_NAMES[plan]} {inviteTerm.words}
            </button>
          )}
        </div>
      </Group>

      <Group title={`Подписки: ${data.subscriptions.length}`} footer={`На пробном Pro: ${data.trials}, навсегда: ${data.lifetime}. Админы не показаны.`}>
        {data.subscriptions.length === 0 && <Row title="Действующих подписок нет" />}
        {data.subscriptions.map((item) => (
          <Row
            key={item.userId}
            title={<>{item.name} <PlanPill plan={item.plan} trial={item.source === 'TRIAL'} /></>}
            subtitle={[SOURCE_NAMES[item.source] ?? item.source, item.recurring ? 'автопродление' : null].filter(Boolean).join(' · ')}
            value={item.expiresAt ? `до ${shortDate(item.expiresAt)}` : 'навсегда'}
            onClick={() => onOpenUser(item.userId)}
          />
        ))}
      </Group>

      {data.payments.length > 0 && (
        <Group title="Последние оплаты">
          {data.payments.map((item) => (
            <Row
              key={`${item.userId}-${item.at}`}
              title={item.name}
              subtitle={`${OFFER_NAMES[item.offer] ?? item.offer}${item.refunded ? ' · возвращено' : ''}`}
              value={<span className={item.refunded ? 'admin-refunded' : undefined}>{item.amount} ⭐ · {shortDate(item.at)}</span>}
              onClick={() => onOpenUser(item.userId)}
            />
          ))}
        </Group>
      )}
    </>
  );
}

// --- Рассылка ---

const AUDIENCES: { id: string; label: string }[] = [
  { id: 'all', label: 'Все' },
  { id: 'mailbox', label: 'С ящиком' },
  { id: 'nobox', label: 'Без ящика' },
  { id: 'paid', label: 'Платные' },
  { id: 'trial', label: 'Пробный Pro' },
  { id: 'free', label: 'Free' },
];

/** Рассылка: текст, аудитория, проверка на себе и отправка с подтверждением. */
export function AdminBroadcastScreen({ notify, onBack }: { notify: Notify; onBack: () => void }) {
  const [text, setText] = useState('');
  const [audience, setAudience] = useState('all');
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [running, setRunning] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    load<{ counts: Record<string, number>; running: boolean }>('/broadcast', notify).then((value) => {
      if (!value) return;
      setCounts(value.counts);
      setRunning(value.running);
    });
  }, [notify]);

  const send = async (test: boolean) => {
    setBusy(true);
    const result = await load<{ message: string }>('/broadcast', notify, 'POST', { text, audience, test });
    setBusy(false);
    setConfirming(false);
    if (!result) return;
    notify('ok', result.message);
    if (!test && result.message.includes('запущена')) {
      setRunning(true);
      setText('');
    }
  };

  const count = counts[audience] ?? 0;
  const ready = text.trim().length > 0 && !busy;

  return (
    <div className="screen">
      <ScreenHeader title="Рассылка" subtitle="Сообщение от бота в личный чат" onBack={onBack} />

      <textarea
        className="admin-textarea"
        placeholder="Текст сообщения"
        value={text}
        maxLength={4000}
        onChange={(event) => { setText(event.target.value); setConfirming(false); }}
      />
      <p className="field-hint">{text.length} / 4000. Отправляется как обычный текст, ссылки кликабельны.</p>

      <Group title="Кому">
        <div className="admin-grant">
          <div className="chips">
            {AUDIENCES.map((item) => (
              <Chip key={item.id} selected={audience === item.id} onClick={() => { setAudience(item.id); setConfirming(false); }}>
                {item.label}{counts[item.id] !== undefined ? ` ${counts[item.id]}` : ''}
              </Chip>
            ))}
          </div>
        </div>
      </Group>

      {running && <p className="field-hint">Идет рассылка: новую можно запустить после ее итога.</p>}

      <button type="button" className="tinted big" disabled={!ready} onClick={() => send(true)}>
        Отправить себе для проверки
      </button>

      <Group footer={confirming ? `Сообщение уйдет ${count} чел. Отменить отправку будет нельзя.` : 'Заблокировавшие бота не получат сообщение. Итог рассылки бот пришлет вам.'}>
        {confirming ? (
          <>
            <Row title={busy ? 'Запускаю…' : `Да, отправить ${count} чел.`} danger onClick={busy ? undefined : () => send(false)} />
            <Row title="Отмена" accent onClick={() => setConfirming(false)} />
          </>
        ) : (
          <Row
            title={`Отправить ${count} чел.`}
            accent
            onClick={ready && !running && count > 0 ? () => setConfirming(true) : undefined}
          />
        )}
      </Group>
    </div>
  );
}
