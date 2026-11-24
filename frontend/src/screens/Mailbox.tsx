import { useState } from 'react'
import { t } from '../i18n'
import { withDefaults, type MailFilters } from '../FiltersPanel'
import { displayName, rulesCount, statusLabel, statusTone, type Account } from '../types'
import { Avatar, Group, Icon, Row, ScreenHeader, StatusLine, SwitchRow } from '../ui'

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
  onDelete: () => Promise<boolean>;
}

// Экран одного ящика: статус, письма, что присылать и куда
export function MailboxScreen({ account, onBack, onOpenMails, onFilters, onGroup, onPassword, onForward, onSaveFilters, onDelete }: Props) {
  const filters = withDefaults(account.filters);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const forward = Boolean(account.forwardAddress);

  const toggle = async (patch: Partial<MailFilters>) => {
    setBusy(true);
    try {
      await onSaveFilters({ ...filters, ...patch });
    } finally {
      setBusy(false);
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
          subtitle={filters.paused ? t.pauseHint : undefined}
          checked={filters.paused}
          disabled={busy}
          onChange={(paused) => toggle({ paused })}
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
    </div>
  );
}
