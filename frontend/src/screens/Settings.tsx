import { useEffect, useState } from 'react'
import { lang, rememberLang, t, type Lang } from '../i18n'
import type { NotificationSettings } from '../NotificationsSection'
import { Group, Icon, Row, ScreenHeader, Switch, SwitchRow } from '../ui'

// Варианты сводки в том же порядке, что и t.digestOptions
const DIGEST_HOURS = [0, 1, 3, 6, 12, 24];

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
  saveLanguage: (value: Lang) => Promise<boolean>;
  sky: boolean;
  onSky: (enabled: boolean) => void;
  onDeleteAll: () => Promise<boolean>;
}

export function SettingsScreen({ load, save, saveLanguage, sky, onSky, onDeleteAll }: Props) {
  const [settings, setSettings] = useState<NotificationSettings | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    load().then((loaded) => loaded && setSettings(loaded));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const update = (patch: Partial<NotificationSettings>) => {
    if (!settings) return;
    setSettings({ ...settings, ...patch });
    setDirty(true);
  };

  const submit = async () => {
    if (!settings) return;
    setSaving(true);
    // Часовой пояс берем из телефона при каждом сохранении: переехал - время сводки сдвинется само
    const saved = await save({ ...settings, timezone: browserTimezone() });
    setSaving(false);
    if (saved) {
      setSettings(saved);
      setDirty(false);
    }
  };

  const chooseLang = async (value: Lang) => {
    if (value === lang) return;
    if (await saveLanguage(value) && rememberLang(value)) window.location.reload();
  };

  const deleteAll = async () => {
    setDeleting(true);
    await onDeleteAll();
    setDeleting(false);
    setConfirmDelete(false);
  };

  return (
    <div className="screen">
      <ScreenHeader large title={t.tabSettings} />

      {settings && (
        <>
          <Group title={t.howToSend} footer={settings.digestHours > 0 ? t.digestOnHint : t.digestOffHint}>
            {DIGEST_HOURS.map((hours, index) => (
              <Row key={hours} title={t.digestOptions[index]} onClick={() => update({ digestHours: hours })}>
                {settings.digestHours === hours && <span className="row-check"><Icon name="check" size={18} /></span>}
              </Row>
            ))}
          </Group>

          <Group title={t.quietHours} footer={`${t.quietHint} ${t.timezone(browserTimezone())}`}>
            <SwitchRow title={t.quietHours} checked={settings.quietEnabled} onChange={(quietEnabled) => update({ quietEnabled })} />
            {settings.quietEnabled && (
              <>
                <Row title={t.from}>
                  <input className="time-input" type="time" aria-label={t.from} value={settings.quietStart} onChange={(e) => update({ quietStart: e.target.value })} />
                </Row>
                <Row title={t.to}>
                  <input className="time-input" type="time" aria-label={t.to} value={settings.quietEnd} onChange={(e) => update({ quietEnd: e.target.value })} />
                </Row>
              </>
            )}
          </Group>

          {dirty && (
            <button type="button" className="primary big" disabled={saving} onClick={submit}>
              {saving ? t.saving : t.save}
            </button>
          )}
        </>
      )}

      <Group>
        <Row icon="globe" iconBg="#2F6FB5" title={t.languageLabel}>
          <div className="mini-segmented" role="group" aria-label={t.languageLabel}>
            {(['ru', 'en'] as const).map((value) => (
              <button key={value} type="button" aria-pressed={lang === value} onClick={() => chooseLang(value)}>
                {value.toUpperCase()}
              </button>
            ))}
          </div>
        </Row>
        <Row icon="sparkle" iconBg="#5A4FB3" title={t.animatedBg} subtitle={t.animatedBgHint}>
          <Switch checked={sky} onChange={onSky} label={t.animatedBg} />
        </Row>
      </Group>

      <Group title={t.myData} footer={confirmDelete ? t.deleteWarning : t.myDataHint}>
        {confirmDelete ? (
          <>
            <Row title={deleting ? t.deleting : t.deleteConfirm} danger onClick={deleting ? undefined : deleteAll} />
            <Row title={t.cancel} accent onClick={() => setConfirmDelete(false)} />
          </>
        ) : (
          <Row icon="trash" iconBg="#8E2F2A" title={t.deleteAll} danger onClick={() => setConfirmDelete(true)} />
        )}
      </Group>
    </div>
  );
}
