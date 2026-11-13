import { useState } from 'react'
import { t } from './i18n'
import { PROVIDERS, providerHint, providerSteps, stepUrl } from './providers'
import { openLink } from './telegram'

interface Props {
  host: string;
  // Адрес ящика: Google открывает страницу паролей сразу в нужном аккаунте
  email: string;
  /** true - вход в ящик сейчас не работает: показываем подсказку заметнее */
  broken: boolean;
  onSave: (password: string) => Promise<boolean>;
}

// Новый пароль приложения для уже подключенного ящика: фильтры и история сохраняются
export function PasswordPanel({ host, email, broken, onSave }: Props) {
  const [password, setPassword] = useState('');
  const [saving, setSaving] = useState(false);
  const provider = PROVIDERS.find((item) => item.host === host);
  // Последний шаг инструкции с кнопкой - страница, где создается пароль приложения
  const passwordStep = provider
    ? [...providerSteps(provider)].reverse().find((step) => typeof step !== 'string')
    : undefined;

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
        {passwordStep && typeof passwordStep !== 'string' && (
          <button type="button" className="step-link" onClick={() => openLink(stepUrl(passwordStep, email))}>
            {passwordStep.button} <span aria-hidden="true">↗</span>
          </button>
        )}
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
