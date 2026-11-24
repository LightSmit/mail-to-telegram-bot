import { useState } from 'react'
import { t } from '../i18n'
import { isPaused, withDefaults, type MailFilters } from '../FiltersPanel'
import { displayName, formatPauseEnd, rulesCount, statusLabel, statusTone, type Account } from '../types'
import { Avatar, Group, Icon, Row, ScreenHeader, Sheet, StatusLine, SwitchRow, WheelPicker } from '../ui'

const HOUR = 60 * 60 * 1000;
// Длительности паузы по строкам барабана; null - пока не выключат
const PAUSE_DURATIONS: (number | null)[] = [HOUR / 2, HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, 24 * HOUR, 72 * HOUR, 168 * HOUR, null];
const DEFAULT_PAUSE = 1;

interface Props {
  account: Account;
  onBack: () => void;
  onOpenMails: () => void;
  onFilters: () => void;
  onGroup: () => void;
  onPassword: () => void;
  onForward: () => void;
  // Сохранить переключатели (пауза, рассылки): фильтры отправляются целиком
  onSaveFilters: (filters: MailFilters) => Promise<boolean>;
  // Проверочное сообщение туда, куда приходят письма
  onTest: () => Promise<boolean>;
  onDelete: () => Promise<boolean>;
}

// Экран одного ящика: статус, письма, что присылать и куда
export function MailboxScreen({ account, onBack, onOpenMails, onFilters, onGroup, onPassword, onForward, onSaveFilters, onTest, onDelete }: Props) {
  const filters = withDefaults(account.filters);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  // Шторка выбора длительности паузы: индекс строки барабана или null - закрыта
  const [pauseChoice, setPauseChoice] = useState<number | null>(null);
  const forward = Boolean(account.forwardAddress);
  const paused = isPaused(filters);

  const toggle = async (patch: Partial<MailFilters>) => {
    setBusy(true);
    try {
      await onSaveFilters({ ...filters, ...patch });
    } finally {
      setBusy(false);
    }
  };

  const applyPause = async (index: number) => {
    const duration = PAUSE_DURATIONS[index];
    setPauseChoice(null);
    await toggle({ paused: true, pausedUntil: duration == null ? null : Date.now() + duration });
  };

  const test = async () => {
    setTesting(true);
    try {
      await onTest();
    } finally {
      setTesting(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      if (await onDelete()) return;
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  };

  return (
    <div className="screen">
      <ScreenHeader title="" onBack={onBack} />

      <div className="mailbox-hero">
        <Avatar text={displayName(account)} size={60} />
        <div className="mailbox-hero-text">
          <h1 className="screen-title">{displayName(account)}</h1>
          <span className="screen-subtitle">
            {account.email} · {forward ? t.forwardBadge : t.appPasswordLabel.toLowerCase()}
          </span>
          <StatusLine tone={statusTone(account)}>{statusLabel(account)}</StatusLine>
        </div>
      </div>

      {forward ? (
        <button type="button" className="primary big" onClick={onForward}>
          <Icon name="mail" size={18} /> {t.forwardRow}
        </button>
      ) : (
        <button type="button" className="primary big" onClick={onOpenMails}>
          <Icon name="mail" size={18} /> {t.openMails}
        </button>
      )}

      <Group title={t.sectionWhat} footer={t.bulkFooter}>
        <SwitchRow
          icon="pause"
          iconBg="#8A6A2B"
          title={t.pause}
          subtitle={paused ? (filters.pausedUntil ? t.pausedUntil(formatPauseEnd(filters.pausedUntil)) : t.pausedForever) : undefined}
          checked={paused}
          disabled={busy}
          // Включение - сначала выбор длительности, выключение - сразу
          onChange={(value) => (value ? setPauseChoice(DEFAULT_PAUSE) : toggle({ paused: false, pausedUntil: null }))}
        />
        <SwitchRow
          icon="list"
          iconBg="#2E7D4F"
          title={t.skipBulk}
          checked={filters.skipBulk}
          disabled={busy}
          onChange={(skipBulk) => toggle({ skipBulk })}
        />
        <Row icon="filter" iconBg="#5A4FB3" title={t.filtersRow} value={t.rulesCount(rulesCount(filters))} chevron onClick={onFilters} />
        <SwitchRow
          icon="bell"
          iconBg="#9C3F6B"
          title={t.sound}
          subtitle={filters.silent ? t.soundOffHint : undefined}
          checked={!filters.silent}
          disabled={busy}
          onChange={(sound) => toggle({ silent: !sound })}
        />
      </Group>

      <Group title={t.sectionWhere}>
        <Row
          icon="group"
          iconBg="#2F6FB5"
          title={t.groupTitle}
          value={account.groupLinked ? (account.groupTitle || t.groupUnnamed) : t.groupNone}
          chevron
          onClick={onGroup}
        />
        {forward ? (
          <Row icon="send" iconBg="#2E6F7D" title={t.forwardRow} value={account.forwardReceived ? t.forwardWorking : undefined} chevron onClick={onForward} />
        ) : (
          <Row icon="lock" iconBg="#4B5A69" title={t.appPasswordLabel} value={t.change} chevron onClick={onPassword} />
        )}
        <Row
          icon="pulse"
          iconBg="#2E7D4F"
          title={t.deliveryTest}
          subtitle={t.deliveryTestHint}
          value={testing ? '…' : undefined}
          onClick={testing ? undefined : test}
        />
      </Group>

      <Group footer={confirming ? t.disconnectAsk(account.email) : undefined}>
        {confirming ? (
          <>
            <Row title={busy ? t.disconnecting : t.disconnectYes} danger onClick={busy ? undefined : remove} />
            <Row title={t.cancel} accent onClick={() => setConfirming(false)} />
          </>
        ) : (
          <Row title={t.disconnectMailbox} danger onClick={() => setConfirming(true)} />
        )}
      </Group>

      {pauseChoice !== null && (
        <Sheet title={t.pauseSheetTitle} onClose={() => setPauseChoice(null)}>
          <p className="sheet-lede">{t.pauseSheetLede}</p>
          <WheelPicker options={t.pauseOptions} value={pauseChoice} onChange={setPauseChoice} label={t.pauseSheetTitle} />
          <p className="sheet-preview">
            {PAUSE_DURATIONS[pauseChoice] == null
              ? t.pausedForever
              : t.pausedUntil(formatPauseEnd(Date.now() + (PAUSE_DURATIONS[pauseChoice] ?? 0)))}
          </p>
          <button type="button" className="primary big" disabled={busy} onClick={() => applyPause(pauseChoice)}>
            <Icon name="pause" size={18} /> {t.pauseApply}
          </button>
          <button type="button" className="sheet-cancel" onClick={() => setPauseChoice(null)}>{t.cancel}</button>
        </Sheet>
      )}
    </div>
  );
}
