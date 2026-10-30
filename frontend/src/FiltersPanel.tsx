import { useState } from 'react'

export interface MailFilters {
  paused: boolean;
  skipBulk: boolean;
  blockedSenders: string[];
  blockedSubjectWords: string[];
  allowedSenders: string[];
}

export const EMPTY_FILTERS: MailFilters = {
  paused: false,
  skipBulk: false,
  blockedSenders: [],
  blockedSubjectWords: [],
  allowedSenders: [],
};

// По одному значению в строке; запятые и точки с запятой тоже разделяют
function toList(text: string): string[] {
  return text
    .split(/[\n,;]+/)
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

interface Props {
  initial: MailFilters;
  onSave: (filters: MailFilters) => Promise<boolean>;
  onClose: () => void;
}

export function FiltersPanel({ initial, onSave, onClose }: Props) {
  const [paused, setPaused] = useState(initial.paused);
  const [skipBulk, setSkipBulk] = useState(initial.skipBulk);
  const [senders, setSenders] = useState(initial.blockedSenders.join('\n'));
  const [words, setWords] = useState(initial.blockedSubjectWords.join('\n'));
  const [allowed, setAllowed] = useState((initial.allowedSenders ?? []).join('\n'));
  const [saving, setSaving] = useState(false);

  const save = async () => {
    setSaving(true);
    const saved = await onSave({
      paused,
      skipBulk,
      blockedSenders: toList(senders),
      blockedSubjectWords: toList(words),
      allowedSenders: toList(allowed),
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
          <span className="toggle-title">Пауза</span>
          <span className="toggle-hint">
            Письма из ящика не приходят. Письма, пришедшие во время паузы, после ее снятия тоже не придут.
          </span>
        </span>
      </label>

      <label className="toggle">
        <input type="checkbox" checked={skipBulk} onChange={(e) => setSkipBulk(e.target.checked)} />
        <span className="toggle-track" aria-hidden="true" />
        <span className="toggle-text">
          <span className="toggle-title">Не присылать рассылки</span>
          <span className="toggle-hint">
            Новости, акции и уведомления сервисов: письма со ссылкой «Отписаться», а также автоответы.
          </span>
        </span>
      </label>

      <label className="field">
        <span className="field-label">Присылать только от</span>
        <textarea
          rows={3}
          placeholder={'boss@company.ru\nbank.ru'}
          value={allowed}
          onChange={(e) => setAllowed(e.target.value)}
        />
        <span className="field-hint">
          Один или несколько адресов или доменов, по одному в строке. Если поле пустое, приходят письма от всех.
        </span>
      </label>

      <label className="field">
        <span className="field-label">Не присылать письма от</span>
        <textarea
          rows={3}
          placeholder={'news@shop.ru\nexample.com'}
          value={senders}
          onChange={(e) => setSenders(e.target.value)}
        />
        <span className="field-hint">Адрес или домен целиком, по одному в строке.</span>
      </label>

      <label className="field">
        <span className="field-label">Не присылать, если в теме есть</span>
        <textarea
          rows={3}
          placeholder={'скидка\nвебинар'}
          value={words}
          onChange={(e) => setWords(e.target.value)}
        />
        <span className="field-hint">Слова или фразы, по одной в строке. Заглавные и строчные буквы не различаются.</span>
      </label>

      <div className="filters-actions">
        <button type="button" className="primary" disabled={saving} onClick={save}>
          {saving ? 'Сохраняем…' : 'Сохранить фильтры'}
        </button>
        <button type="button" className="secondary" disabled={saving} onClick={onClose}>
          Закрыть
        </button>
      </div>
    </div>
  );
}
