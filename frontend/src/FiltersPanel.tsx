import { useState } from 'react'
import { t } from './i18n'

export interface MailFilters {
  paused: boolean;
  skipBulk: boolean;
  blockedSenders: string[];
  blockedSubjectWords: string[];
  allowedSenders: string[];
}

/** Дополняет недостающие поля значениями по умолчанию (на случай неполного ответа сервера). */
export function withDefaults(filters?: Partial<MailFilters> | null): MailFilters {
  return {
    paused: filters?.paused ?? false,
    skipBulk: filters?.skipBulk ?? false,
    blockedSenders: filters?.blockedSenders ?? [],
    blockedSubjectWords: filters?.blockedSubjectWords ?? [],
    allowedSenders: filters?.allowedSenders ?? [],
  };
}

// По одному значению в строке; запятые и точки с запятой тоже разделяют
function toList(text: string): string[] {
  return text
    .split(/[\n,;]+/)
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

interface Props {
  initial: MailFilters;
  // false - тариф Free: списки отправителей и слов недоступны (сохраненные не действуют)
  pro?: boolean;
  onUpgrade?: () => void;
  onSave: (filters: MailFilters) => Promise<boolean>;
  onClose: () => void;
}

export function FiltersPanel({ initial: rawInitial, pro = true, onUpgrade, onSave, onClose }: Props) {
  const initial = withDefaults(rawInitial);
  const [paused, setPaused] = useState(initial.paused);
  const [skipBulk, setSkipBulk] = useState(initial.skipBulk);
  const [senders, setSenders] = useState(initial.blockedSenders.join('\n'));
  const [words, setWords] = useState(initial.blockedSubjectWords.join('\n'));
  const [allowed, setAllowed] = useState(initial.allowedSenders.join('\n'));
  const [saving, setSaving] = useState(false);

  const save = async () => {
    setSaving(true);
    // На Free списки не меняются: отправляем сохраненные как есть
    const saved = await onSave({
      paused,
      skipBulk,
      blockedSenders: pro ? toList(senders) : initial.blockedSenders,
      blockedSubjectWords: pro ? toList(words) : initial.blockedSubjectWords,
      allowedSenders: pro ? toList(allowed) : initial.allowedSenders,
    });
    setSaving(false);
    if (saved) onClose();
  };

  return (
    <div className="filters">
      <label className="toggle">
        <input type="checkbox" checked={paused} onChange={(e) => setPaused(e.target.checked)} />
        <span className="toggle-track" aria-hidden="true" />
        <span className="toggle-text">
          <span className="toggle-title">{t.pause}</span>
          <span className="toggle-hint">{t.pauseHint}</span>
        </span>
      </label>

      <label className="toggle">
        <input type="checkbox" checked={skipBulk} onChange={(e) => setSkipBulk(e.target.checked)} />
        <span className="toggle-track" aria-hidden="true" />
        <span className="toggle-text">
          <span className="toggle-title">{t.skipBulk}</span>
          <span className="toggle-hint">{t.skipBulkHint}</span>
        </span>
      </label>

      {!pro && (
        <p className="pro-hint">
          <span className="pro-badge">{t.proBadge}</span> {t.proFiltersHint}
          {onUpgrade && (
            <button type="button" className="link-button" onClick={onUpgrade}>{t.showPlans}</button>
          )}
        </p>
      )}

      <label className={pro ? 'field' : 'field field-locked'}>
        <span className="field-label">{t.onlyFrom}</span>
        <textarea
          rows={3}
          placeholder={'boss@company.ru\nbank.ru'}
          value={allowed}
          disabled={!pro}
          onChange={(e) => setAllowed(e.target.value)}
        />
        <span className="field-hint">{t.onlyFromHint}</span>
      </label>

      <label className={pro ? 'field' : 'field field-locked'}>
        <span className="field-label">{t.notFrom}</span>
        <textarea
          rows={3}
          placeholder={'news@shop.ru\nexample.com'}
          value={senders}
          disabled={!pro}
          onChange={(e) => setSenders(e.target.value)}
        />
        <span className="field-hint">{t.notFromHint}</span>
      </label>

      <label className={pro ? 'field' : 'field field-locked'}>
        <span className="field-label">{t.notSubject}</span>
        <textarea
          rows={3}
          placeholder={t.subjectPlaceholder}
          value={words}
          disabled={!pro}
          onChange={(e) => setWords(e.target.value)}
        />
        <span className="field-hint">{t.notSubjectHint}</span>
      </label>

      <div className="filters-actions">
        <button type="button" className="primary" disabled={saving} onClick={save}>
          {saving ? t.saving : t.saveFilters}
        </button>
        <button type="button" className="secondary" disabled={saving} onClick={onClose}>
          {t.close}
        </button>
      </div>
    </div>
  );
}
