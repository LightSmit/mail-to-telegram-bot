import { useEffect, useState } from 'react'
import { t } from '../i18n'
import { CUSTOM_PROVIDER_ID, PROVIDERS, detectProvider, findProvider, providerTitle } from '../providers'
import { Chip, Icon, ScreenHeader } from '../ui'

export type AddMode = 'forward' | 'password';

interface Props {
  forwardingEnabled: boolean;
  busy: boolean;
  onBack: () => void;
  // Почта выбрана (для воронки: сервер записывает только первый выбор)
  onProvider: (providerId: string) => void;
  onContinue: (mode: AddMode, email: string, providerId: string) => void;
}

// Шаг 1 подключения: адрес почты и способ - пересылка без пароля или пароль приложения
export function AddScreen({ forwardingEnabled, busy, onBack, onProvider, onContinue }: Props) {
  const [email, setEmail] = useState('');
  const [providerId, setProviderId] = useState('');
  const [touched, setTouched] = useState(false);
  const [mode, setMode] = useState<AddMode>(forwardingEnabled ? 'forward' : 'password');

  useEffect(() => {
    if (providerId) onProvider(providerId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [providerId]);

  const changeEmail = (value: string) => {
    setEmail(value);
    if (!touched) setProviderId(detectProvider(value)?.id ?? (value.includes('@') && value.split('@')[1]?.includes('.') ? CUSTOM_PROVIDER_ID : ''));
  };

  const pick = (id: string) => {
    setTouched(true);
    setProviderId(id);
  };

  const detected = findProvider(providerId);
  const valid = /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim());

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid || busy) return;
    onContinue(forwardingEnabled ? mode : 'password', email.trim(), providerId || CUSTOM_PROVIDER_ID);
  };

  return (
    <form className="screen" onSubmit={submit}>
      <ScreenHeader title={t.addTitle} subtitle={t.addStep1} onBack={onBack} />

      <section className="group">
        <label htmlFor="add-email" className="group-title">{t.emailLabel}</label>
        <div className={valid ? 'input-box focused' : 'input-box'}>
          <input
            id="add-email"
            type="email"
            inputMode="email"
            autoComplete="email"
            autoCapitalize="none"
            placeholder="name@gmail.com"
            value={email}
            onChange={(e) => changeEmail(e.target.value)}
          />
          {detected && <span className="detected">{providerTitle(detected)}</span>}
        </div>
        <div className="chips-row">
          {PROVIDERS.map((provider) => (
            <Chip key={provider.id} selected={providerId === provider.id} onClick={() => pick(provider.id)}>
              {providerTitle(provider)}
            </Chip>
          ))}
          <Chip selected={providerId === CUSTOM_PROVIDER_ID} onClick={() => pick(CUSTOM_PROVIDER_ID)}>{t.otherProvider}</Chip>
        </div>
      </section>

      {forwardingEnabled && (
        <section className="group">
          <h2 className="group-title">{t.howToConnect}</h2>
          <div className="mode-cards" role="radiogroup" aria-label={t.howToConnect}>
            <button type="button" role="radio" aria-checked={mode === 'forward'} className="mode-card" onClick={() => setMode('forward')}>
              <span className="mode-head">
                <span className="radio" aria-hidden="true" />
                <span className="mode-title">{t.modeForward}</span>
                <span className="badge badge-green">{t.recommended}</span>
              </span>
              <span className="mode-text">{t.forwardCardText}</span>
              <span className="mode-points">
                {t.forwardPlus.map((point) => <span key={point} className="plus">{point}</span>)}
                <span className="minus">{t.forwardMinus}</span>
              </span>
            </button>
            <button type="button" role="radio" aria-checked={mode === 'password'} className="mode-card" onClick={() => setMode('password')}>
              <span className="mode-head">
                <span className="radio" aria-hidden="true" />
                <span className="mode-title">{t.modePassword}</span>
              </span>
              <span className="mode-text">{t.passwordCardText}</span>
              <span className="mode-points">
                {t.passwordPlus.map((point) => <span key={point} className="plus">{point}</span>)}
              </span>
            </button>
          </div>
        </section>
      )}

      <div className="bottom-actions sticky">
        <button type="submit" className="primary big" disabled={!valid || busy}>
          {busy ? t.forwardCreating : t.continueButton}
          {!busy && <Icon name="chevron-right" size={16} />}
        </button>
      </div>
    </form>
  );
}
