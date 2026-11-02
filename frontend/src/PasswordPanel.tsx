import { useState } from 'react'
import { t } from './i18n'
import { PROVIDERS, providerHint } from './providers'

interface Props {
  host: string;
  /** true - вход в ящик сейчас не работает: показываем подсказку заметнее */
  broken: boolean;
  onSave: (password: string) => Promise<boolean>;
}

// Новый пароль приложения для уже подключенного ящика: фильтры и история сохраняются
export function PasswordPanel({ host, broken, onSave }: Props) {
  const [password, setPassword] = useState('');
  const [saving, setSaving] = useState(false);
  const provider = PROVIDERS.find((item) => item.host === host);

  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!password.trim()) return;
    setSaving(true);
    const saved = await onSave(password.trim());
    setSaving(false);
    if (saved) setPassword('');
  };

  return (
    <form className={broken ? 'filters password-panel broken' : 'filters password-panel'} onSubmit={save}>
      <div className="toggle-text">
        <span className="toggle-title">{t.appPasswordLabel}</span>
        <span className="toggle-hint">
          {broken ? t.passwordBrokenHint : t.passwordHint}
        </span>
      </div>

      <label className="field">
        <span className="field-label">{t.newAppPassword}</span>
        <input
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          disabled={saving}
        />
        {provider && <span className="field-hint">{providerHint(provider)}</span>}
      </label>

      <button type="submit" className="primary" disabled={saving || !password.trim()}>
        {saving ? t.checkingShort : t.updatePassword}
      </button>
    </form>
  );
}
