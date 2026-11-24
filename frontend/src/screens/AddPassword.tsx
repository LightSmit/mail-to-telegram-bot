import { useState } from 'react'
import { t } from '../i18n'
import { CUSTOM_PROVIDER_ID, CUSTOM_STEPS, PROVIDERS, findProvider, providerHint, providerSteps, providerTitle, stepKey, stepUrl } from '../providers'
import { openLink } from '../telegram'
import { Chip, Icon, ScreenHeader } from '../ui'

export interface PasswordForm {
  email: string;
  host: string;
  password: string;
  name: string;
}

interface Props {
  email: string;
  initialProviderId: string;
  forwardingEnabled: boolean;
  busy: boolean;
  onBack: () => void;
  onSubmit: (form: PasswordForm) => void;
  onSwitchToForward: () => void;
}

// Шаг 2 для пароля приложения: инструкция для выбранной почты и поле пароля
export function AddPassword({ email, initialProviderId, forwardingEnabled, busy, onBack, onSubmit, onSwitchToForward }: Props) {
  const [providerId, setProviderId] = useState(initialProviderId || CUSTOM_PROVIDER_ID);
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [customHost, setCustomHost] = useState('');

  const provider = findProvider(providerId);
  const custom = !provider;
  const steps = provider ? providerSteps(provider) : CUSTOM_STEPS;
  const host = provider ? provider.host : customHost.trim();
  const ready = password.trim().length > 0 && host.length > 0;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!ready || busy) return;
    onSubmit({ email, host, password: password.trim(), name: name.trim() });
  };

  return (
    <form className="screen" onSubmit={submit}>
      <ScreenHeader title={t.appPasswordLabel} subtitle={t.appPasswordScreenLede} onBack={onBack} />

      <div className="chips-row">
        {PROVIDERS.map((item) => (
          <Chip key={item.id} selected={providerId === item.id} onClick={() => setProviderId(item.id)}>{providerTitle(item)}</Chip>
        ))}
        <Chip selected={custom} onClick={() => setProviderId(CUSTOM_PROVIDER_ID)}>{t.otherProvider}</Chip>
      </div>

      <ol className="steps-card">
        {steps.map((step, index) => (
          <li key={stepKey(step)}>
            <span className="step-num">{index + 1}</span>
            <span className="step-body">
              {typeof step === 'string' ? step : (
                <>
                  {step.text}
                  <button type="button" className="tinted small step-button" onClick={() => openLink(stepUrl(step, email))}>
                    {step.button} <Icon name="external" size={14} />
                  </button>
                </>
              )}
            </span>
          </li>
        ))}
      </ol>

      <section className="group">
        {custom && (
          <>
            <label htmlFor="imap-host" className="group-title">{t.imapServer}</label>
            <div className="input-box">
              <input id="imap-host" placeholder="imap.example.com" autoCapitalize="none" value={customHost} onChange={(e) => setCustomHost(e.target.value)} />
            </div>
          </>
        )}
        <label htmlFor="app-password" className="group-title">{t.appPasswordLabel}</label>
        <div className="input-box">
          <input
            id="app-password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {provider && <p className="group-footer">{providerHint(provider)}</p>}

        <label htmlFor="mailbox-name" className="group-title">{t.nameLabel} <span className="optional">{t.optional}</span></label>
        <div className="input-box">
          <input id="mailbox-name" placeholder={t.namePlaceholder} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
      </section>

      <div className="bottom-actions sticky">
        <button type="submit" className="primary big" disabled={!ready || busy}>
          {busy ? t.checkingShort : t.connect}
        </button>
        {forwardingEnabled && (
          <button type="button" className="text-button" onClick={onSwitchToForward}>{t.cantConnect}</button>
        )}
      </div>
    </form>
  );
}
