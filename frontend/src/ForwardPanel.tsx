import { useState } from 'react'
import { t } from './i18n'
import { detectProvider, forwardSteps, stepKey, stepUrl } from './providers'
import { openLink } from './telegram'

interface Props {
  // Адрес бота, на который пересылаются письма ящика
  address: string;
  // Адрес самого ящика: по нему выбирается инструкция
  email: string;
  // Ящик только что подключен: показываем подсказку «остался один шаг»
  fresh?: boolean;
}

// Ящик без пароля: адрес для пересылки, кнопка «Скопировать» и инструкция для почтового сервиса
export function ForwardPanel({ address, email, fresh }: Props) {
  const [copied, setCopied] = useState(false);
  const steps = forwardSteps(detectProvider(email));

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
    <div className="filters forward-panel">
      <div className="toggle-text">
        <span className="toggle-title">{fresh ? t.forwardLastStep : t.forwardAddressTitle}</span>
        <span className="toggle-hint">{t.forwardAddressHint}</span>
      </div>

      <code className="referral-link forward-address">{address}</code>
      <button type="button" className="secondary" onClick={copy}>{copied ? t.referralCopied : t.forwardCopy}</button>

      <ol className="steps">
        {steps.map((step) => (
          <li key={stepKey(step)}>
            {typeof step === 'string' ? step : (
              <>
                {step.text}
                <br />
                <button type="button" className="step-link" onClick={() => openLink(stepUrl(step, email))}>
                  {step.button} <span aria-hidden="true">↗</span>
                </button>
              </>
            )}
          </li>
        ))}
      </ol>

      <p className="field-hint">{t.forwardLimits}</p>
    </div>
  );
}
