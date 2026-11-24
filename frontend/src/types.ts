import { t } from './i18n'
import { isPaused, type MailFilters } from './FiltersPanel'
import type { Tone } from './ui'

export type MailboxStatus = 'CONNECTING' | 'OK' | 'ERROR';

export interface Account {
  id: number;
  name: string;
  email: string;
  host: string;
  status?: MailboxStatus;
  problem?: string | null;
  filters?: MailFilters;
  // Ящик сверх лимита тарифа: письма из него не приходят
  overLimit?: boolean;
  // Письма ящика приходят в групповой чат (Business)
  groupLinked?: boolean;
  groupTitle?: string | null;
  // Ящик без пароля: письма пересылаются на этот адрес бота
  forwardAddress?: string | null;
  forwardReceived?: boolean;
}

// Режим «Пересылка без пароля» на сервере
export interface Forwarding {
  enabled: boolean;
  domain?: string | null;
}

export type NoticeKind = 'info' | 'ok' | 'error';
export type Notify = (kind: NoticeKind, text: string) => void;

/** Название ящика для заголовков: свое имя, если задано, иначе адрес. */
export const displayName = (account: Account) => (account.name && account.name !== account.email ? account.name : account.email);

export function statusLabel(account: Account): string {
  if (isPaused(account.filters)) {
    const until = account.filters?.pausedUntil;
    return until ? t.statusPausedUntil(formatPauseEnd(until)) : t.statusPaused;
  }
  if (account.overLimit) return t.statusOverLimit;
  if (account.forwardAddress) return account.forwardReceived ? t.forwardWorking : t.forwardStatus;
  switch (account.status) {
    case 'OK':
      return t.statusOk;
    case 'ERROR':
      return account.problem ?? t.statusError;
    default:
      return t.statusConnecting;
  }
}

export function statusTone(account: Account): Tone {
  if (isPaused(account.filters) || account.overLimit) return 'muted';
  if (account.forwardAddress) return account.forwardReceived ? 'ok' : 'warn';
  if (account.status === 'ERROR') return 'error';
  if (account.status === 'OK') return 'ok';
  return 'warn';
}

/** Сколько правил фильтрации задано (для строки «Фильтры»). */
export function rulesCount(filters?: MailFilters): number {
  if (!filters) return 0;
  return filters.allowedSenders.length + filters.blockedSenders.length + filters.blockedSubjectWords.length;
}

/** Конец паузы: сегодня - только время, иначе день, месяц и время. */
export function formatPauseEnd(millis: number, now = new Date()): string {
  const date = new Date(millis);
  const time = date.toLocaleTimeString(t.locale, { hour: '2-digit', minute: '2-digit' });
  if (date.toDateString() === now.toDateString()) return time;
  return `${date.toLocaleDateString(t.locale, { day: 'numeric', month: 'short' })}, ${time}`;
}
