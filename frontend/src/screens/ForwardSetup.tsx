import { useState } from 'react'
import { t } from '../i18n'
import { CUSTOM_PROVIDER_ID, PROVIDERS, detectProvider, findProvider, forwardSteps, providerTitle, stepKey, stepUrl } from '../providers'
import { openLink } from '../telegram'
import type { Account } from '../types'
import { Chip, Icon, ScreenHeader, StatusLine } from '../ui'

interface Props {
  account: Account;
  onBack: () => void;
  onDone: () => void;
}

// Шаг 2 для режима без пароля: адрес для пересылки и инструкция для выбранной почты
export function ForwardSetup({ account, onBack, onDone }: Props) {
  const [copied, setCopied] = useState(false);
  const [providerId, setProviderId] = useState(detectProvider(account.email)?.id ?? CUSTOM_PROVIDER_ID);
  const address = account.forwardAddress ?? '';
  const provider = findProvider(providerId);
  const steps = forwardSteps(provider);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(address);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // буфер обмена недоступен: адрес можно выделить вручную
    }
  };

  return (
    <div className="screen">
      <ScreenHeader
        title={account.forwardReceived ? t.forwardAddressTitle : t.enableForwardTitle}
        subtitle={account.forwardReceived ? account.email : t.step2(account.email)}
        onBack={onBack}
      />

      <div className="address-card">
        <span className="address-text">
          <span className="address-label">{t.yourForwardAddress}</span>
          <code className="address-value">{address}</code>
        </span>
        <button type="button" className="tinted small" onClick={copy}>
          <Icon name={copied ? 'check' : 'copy'} size={16} /> {copied ? t.copiedShort : t.copy}
        </button>
      </div>

      <StatusLine tone={account.forwardReceived ? 'ok' : 'warn'}>
        {account.forwardReceived ? t.forwardWorking : t.forwardStatus}
      </StatusLine>

      <div className="chips-row">
        {PROVIDERS.filter((item) => item.forwardSteps).map((item) => (
          <Chip key={item.id} selected={providerId === item.id} onClick={() => setProviderId(item.id)}>{providerTitle(item)}</Chip>
        ))}
        <Chip selected={!provider?.forwardSteps} onClick={() => setProviderId(CUSTOM_PROVIDER_ID)}>{t.otherProvider}</Chip>
      </div>

      <ol className="steps-card">
        {steps.map((step, index) => (
          <li key={stepKey(step)}>
            <span className="step-num">{index + 1}</span>
            <span className="step-body">
              {typeof step === 'string' ? step : (
                <>
                  {step.text}
                  <button type="button" className="tinted small step-button" onClick={() => openLink(stepUrl(step, account.email))}>
                    {step.button} <Icon name="external" size={14} />
                  </button>
                </>
              )}
            </span>
          </li>
        ))}
      </ol>

      <p className="group-footer">{t.forwardLimits}</p>
      {!account.forwardReceived && <p className="group-footer">{t.forwardAutoNote}</p>}

      <div className="bottom-actions sticky">
        <button type="button" className="primary big" onClick={onDone}>{t.done}</button>
      </div>
    </div>
  );
}
