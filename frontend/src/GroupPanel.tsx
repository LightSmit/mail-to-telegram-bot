import { useState } from 'react'
import { t } from './i18n'
import { openTelegramLink } from './telegram'

interface Props {
  // Название группы, если письма ящика уже приходят в группу
  groupTitle?: string | null;
  linked: boolean;
  // Тариф Business: без него пересылка в группу недоступна
  business: boolean;
  createLink: () => Promise<string | null>;
  unlink: () => Promise<boolean>;
  onUpgrade?: () => void;
}

// Пересылка писем ящика в групповой чат команды (Business)
export function GroupPanel({ groupTitle, linked, business, createLink, unlink, onUpgrade }: Props) {
  const [busy, setBusy] = useState(false);
  const [waiting, setWaiting] = useState(false);

  const choose = async () => {
    setBusy(true);
    try {
      const link = await createLink();
      if (link) {
        openTelegramLink(link);
        setWaiting(true);
      }
    } finally {
      setBusy(false);
    }
  };

  const disconnect = async () => {
    setBusy(true);
    try {
      await unlink();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="filters group-panel">
      <span className="field-label">
        {t.groupTitle}{!business && <span className="pro-badge">Business</span>}
      </span>

      {linked ? (
        <>
          <p className="group-linked">👥 {t.groupLinkedTo(groupTitle || t.groupUnnamed)}</p>
          {!business && <p className="pro-hint">{t.groupInactive}</p>}
          <button type="button" className="secondary" disabled={busy} onClick={disconnect}>
            {t.groupDisconnect}
          </button>
        </>
      ) : business ? (
        <>
          <span className="field-hint">{t.groupHint}</span>
          <button type="button" className="secondary group-choose" disabled={busy} onClick={choose}>
            {t.groupChoose}
          </button>
          {waiting && <span className="field-hint">{t.groupWaiting}</span>}
        </>
      ) : (
        <p className="pro-hint">
          {t.groupBusinessHint}
          {onUpgrade && (
            <button type="button" className="link-button" onClick={onUpgrade}>{t.showPlans}</button>
          )}
        </p>
      )}
    </div>
  );
}
