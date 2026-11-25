import { useState } from 'react'
import { t } from '../i18n'
import { withDefaults, type MailFilters } from '../FiltersPanel'
import type { Account } from '../types'
import { Icon, ScreenHeader } from '../ui'

interface Props {
  account: Account;
  // false - тариф Free: списки недоступны (сохраненные не действуют)
  pro: boolean;
  onUpgrade?: () => void;
  onBack: () => void;
  onSave: (filters: MailFilters) => Promise<boolean>;
}

type ListKey = 'vipSenders' | 'allowedSenders' | 'blockedSenders' | 'blockedSubjectWords';

// Значения из поля: по одному, запятые, точки с запятой и переносы тоже разделяют
const split = (text: string) => text.split(/[\n,;]+/).map((value) => value.trim()).filter(Boolean);

// Фильтры ящика: списки в виде меток с крестиком
export function FiltersScreen({ account, pro, onUpgrade, onBack, onSave }: Props) {
  const initial = withDefaults(account.filters);
  const [lists, setLists] = useState<Record<ListKey, string[]>>({
    vipSenders: initial.vipSenders ?? [],
    allowedSenders: initial.allowedSenders,
    blockedSenders: initial.blockedSenders,
    blockedSubjectWords: initial.blockedSubjectWords,
  });
  const [drafts, setDrafts] = useState<Record<ListKey, string>>({
    vipSenders: '', allowedSenders: '', blockedSenders: '', blockedSubjectWords: '',
  });
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  const add = (key: ListKey) => {
    const values = split(drafts[key]);
    if (values.length === 0) return;
    setLists((current) => {
      const known = new Set(current[key].map((value) => value.toLowerCase()));
      return { ...current, [key]: [...current[key], ...values.filter((value) => !known.has(value.toLowerCase()))] };
    });
    setDrafts((current) => ({ ...current, [key]: '' }));
    setDirty(true);
  };

  const remove = (key: ListKey, value: string) => {
    setLists((current) => ({ ...current, [key]: current[key].filter((item) => item !== value) }));
    setDirty(true);
  };

  const save = async () => {
    setSaving(true);
    // Недописанное в поле тоже сохраняем, чтобы не потерять
    const merged = { ...lists };
    (Object.keys(drafts) as ListKey[]).forEach((key) => {
      const extra = split(drafts[key]).filter((value) => !merged[key].includes(value));
      merged[key] = [...merged[key], ...extra];
    });
    const ok = await onSave({ ...initial, ...merged });
    setSaving(false);
    if (ok) onBack();
  };

  const sections: { key: ListKey; title: string; hint: string; placeholder: string; empty?: string }[] = [
    { key: 'vipSenders', title: t.vipSenders, hint: t.vipSendersHint, placeholder: t.addSenderPlaceholder },
    { key: 'allowedSenders', title: t.onlyFrom, hint: t.onlyFromHint, placeholder: t.addSenderPlaceholder, empty: t.allSenders },
    { key: 'blockedSenders', title: t.notFrom, hint: t.notFromHint, placeholder: t.addSenderPlaceholder },
    { key: 'blockedSubjectWords', title: t.notSubject, hint: t.notSubjectHint, placeholder: t.addWordPlaceholder },
  ];

  return (
    <div className="screen">
      <ScreenHeader title={t.filtersRow} subtitle={t.filtersLede(account.email)} onBack={onBack} />

      {!pro && (
        <p className="pro-banner">
          <span className="badge badge-violet">{t.proBadge}</span> {t.proFiltersHint}
          {onUpgrade && <button type="button" className="text-button inline" onClick={onUpgrade}>{t.showPlans}</button>}
        </p>
      )}

      {sections.map((section) => (
        <section key={section.key} className={pro ? 'group' : 'group locked'}>
          <h2 className="group-title">{section.title}</h2>
          <div className="group-card tokens-card">
            {lists[section.key].length > 0 ? (
              <div className="tokens">
                {lists[section.key].map((value) => (
                  <span key={value} className="token">
                    {value}
                    <button type="button" className="token-remove" aria-label={t.removeItem(value)} disabled={!pro} onClick={() => remove(section.key, value)}>
                      <Icon name="close" size={12} />
                    </button>
                  </span>
                ))}
              </div>
            ) : section.empty ? <span className="tokens-empty">{section.empty}</span> : null}
            <div className="token-input">
              <input
                placeholder={section.placeholder}
                aria-label={section.title}
                value={drafts[section.key]}
                disabled={!pro}
                autoCapitalize="none"
                onChange={(e) => setDrafts((current) => ({ ...current, [section.key]: e.target.value }))}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    add(section.key);
                  }
                }}
              />
              {drafts[section.key].trim() && (
                <button type="button" className="tinted small" onClick={() => add(section.key)}>{t.addItem}</button>
              )}
            </div>
          </div>
          <p className="group-footer">{section.hint}</p>
        </section>
      ))}

      <div className="bottom-actions sticky">
        <button type="button" className="primary big" disabled={saving || !pro || (!dirty && !Object.values(drafts).some((v) => v.trim()))} onClick={save}>
          {saving ? t.saving : t.save}
        </button>
      </div>
    </div>
  );
}
