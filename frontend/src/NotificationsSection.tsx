import { useEffect, useState } from 'react'

export interface NotificationSettings {
  timezone: string;
  quietEnabled: boolean;
  quietStart: string;
  quietEnd: string;
  digestHours: number;
}

const DIGEST_OPTIONS: { hours: number; label: string }[] = [
  { hours: 0, label: 'Сразу, каждое письмо' },
  { hours: 1, label: 'Сводкой раз в час' },
  { hours: 3, label: 'Сводкой раз в 3 часа' },
  { hours: 6, label: 'Сводкой раз в 6 часов' },
  { hours: 12, label: 'Сводкой дважды в день (9:00 и 21:00)' },
  { hours: 24, label: 'Сводкой раз в день (9:00)' },
];

function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Moscow';
  } catch {
    return 'Europe/Moscow';
  }
}

interface Props {
  load: () => Promise<NotificationSettings | null>;
  save: (settings: NotificationSettings) => Promise<NotificationSettings | null>;
}

// Как присылать письма: тихие часы (без звука) и сводка (пачкой в заданное время)
export function NotificationsSection({ load, save }: Props) {
  const [settings, setSettings] = useState<NotificationSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    load().then((loaded) => {
      if (loaded) setSettings(loaded);
    });
    // load не меняется между отрисовками по смыслу: загружаем один раз
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!settings) return null;

  const update = (patch: Partial<NotificationSettings>) => {
    setSettings({ ...settings, ...patch });
    setDirty(true);
  };

  const submit = async () => {
    setSaving(true);
    // Часовой пояс берем из телефона при каждом сохранении: переехал - время сводки сдвинется само
    const saved = await save({ ...settings, timezone: browserTimezone() });
    setSaving(false);
    if (saved) {
      setSettings(saved);
      setDirty(false);
    }
  };

  return (
    <section className="section" aria-labelledby="notify-title">
      <h2 id="notify-title" className="section-title">Уведомления</h2>

      <div className="filters notify">
        <label className="field">
          <span className="field-label">Как присылать письма</span>
          <select
            value={settings.digestHours}
            onChange={(e) => update({ digestHours: Number(e.target.value) })}
          >
            {DIGEST_OPTIONS.map((option) => (
              <option key={option.hours} value={option.hours}>{option.label}</option>
            ))}
          </select>
          <span className="field-hint">
            {settings.digestHours > 0
              ? 'Письма копятся и приходят пачкой в нужное время. Звук только у первого письма в пачке.'
              : 'Каждое письмо приходит в чат в течение нескольких секунд.'}
          </span>
        </label>

        <label className="toggle">
          <input
            type="checkbox"
            checked={settings.quietEnabled}
            onChange={(e) => update({ quietEnabled: e.target.checked })}
          />
          <span className="toggle-track" aria-hidden="true" />
          <span className="toggle-text">
            <span className="toggle-title">Тихие часы</span>
            <span className="toggle-hint">В это время письма приходят без звука.</span>
          </span>
        </label>

        {settings.quietEnabled && (
          <div className="time-range">
            <label className="field">
              <span className="field-label">С</span>
              <input type="time" value={settings.quietStart} onChange={(e) => update({ quietStart: e.target.value })} />
            </label>
            <label className="field">
              <span className="field-label">До</span>
              <input type="time" value={settings.quietEnd} onChange={(e) => update({ quietEnd: e.target.value })} />
            </label>
          </div>
        )}

        <span className="field-hint">Часовой пояс: {browserTimezone()}</span>

        {dirty && (
          <button type="button" className="primary" disabled={saving} onClick={submit}>
            {saving ? 'Сохраняем…' : 'Сохранить'}
          </button>
        )}
      </div>
    </section>
  );
}
