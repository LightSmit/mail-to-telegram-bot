import { useState } from 'react'
import { t } from './i18n'
import { openInvoice } from './telegram'

export type PlanId = 'FREE' | 'PRO' | 'BUSINESS';

export interface Offer {
  id: string;
  plan: PlanId;
  period: 'month' | 'year';
  stars: number;
}

export interface PlanInfo {
  billingEnabled: boolean;
  plan: PlanId;
  expiresAt?: number | null;
  lifetime: boolean;
  recurring: boolean;
  trial: boolean;
  admin: boolean;
  maxMailboxes: number;
  offers: Offer[];
}

/** Платный тариф: фильтры по отправителям, все письма с поиском, коды подтверждения. */
export const hasPro = (info: PlanInfo | null): boolean => !info || info.plan !== 'FREE';

interface Props {
  info: PlanInfo;
  // Получить ссылку на оплату (null - не удалось, причину показывает App)
  createInvoice: (offerId: string) => Promise<string | null>;
  // После оплаты: перечитать тариф с сервера
  onPaid: () => Promise<void>;
  notify: (kind: 'info' | 'ok' | 'error', text: string) => void;
}

function formatDate(millis: number): string {
  return new Date(millis).toLocaleDateString(t.locale, { day: 'numeric', month: 'long', year: 'numeric' });
}

function statusLine(info: PlanInfo): string {
  if (info.admin) return t.planAdmin;
  if (info.lifetime) return t.planLifetime;
  if (info.expiresAt) {
    const date = formatDate(info.expiresAt);
    if (info.trial) return t.planTrialUntil(date);
    if (info.recurring) return t.planRenews(date);
    return t.planUntil(date);
  }
  return t.planFreeHint;
}

// Скидка годового тарифа относительно 12 месяцев
function yearSaving(offers: Offer[], year: Offer): number {
  const month = offers.find((offer) => offer.plan === year.plan && offer.period === 'month');
  if (!month) return 0;
  return Math.round((1 - year.stars / (month.stars * 12)) * 100);
}

export function PlanSection({ info, createInvoice, onPaid, notify }: Props) {
  const [busy, setBusy] = useState<string | null>(null);

  const buy = async (offer: Offer) => {
    setBusy(offer.id);
    notify('info', t.paying);
    try {
      const link = await createInvoice(offer.id);
      if (!link) return;
      const status = await openInvoice(link);
      if (status === 'paid') {
        notify('ok', t.paymentDone);
        await onPaid();
      } else if (status === 'pending') {
        notify('info', t.paymentPending);
        await onPaid();
      } else if (status === 'failed') {
        notify('error', t.paymentFailed);
      } else if (status === 'unsupported') {
        notify('error', t.paymentNoTelegram);
      } else {
        notify('info', '');
      }
    } finally {
      setBusy(null);
    }
  };

  const plans: PlanId[] = ['PRO', 'BUSINESS'];

  return (
    <section className="section" aria-labelledby="plan-title" id="plan">
      <h2 id="plan-title" className="section-title">{t.planTitle}</h2>

      <div className={`plan-card plan-${info.plan.toLowerCase()}`}>
        <div className="plan-current">
          <span className="plan-name">{t.planName(info.plan)}</span>
          <span className="plan-status">{statusLine(info)}</span>
        </div>
        {info.plan !== 'FREE' && <span className="field-hint">{t.planMailboxes(info.maxMailboxes)}</span>}
        {info.recurring && <span className="field-hint">{t.planCancelHint}</span>}
      </div>

      {plans.map((plan) => {
        const offers = info.offers.filter((offer) => offer.plan === plan);
        if (offers.length === 0) return null;
        const perks = plan === 'BUSINESS' ? t.businessPerks : t.proPerks;
        return (
          <div key={plan} className="plan-offer">
            <h3 className="plan-offer-title">
              {info.plan === plan ? t.planRenew(t.planName(plan)) : t.planName(plan)}
            </h3>
            {info.plan !== plan && (
              <ul className="plan-perks">
                {perks.map((perk) => <li key={perk}>{perk}</li>)}
              </ul>
            )}
            <div className="plan-buttons">
              {offers.map((offer) => {
                const saving = offer.period === 'year' ? yearSaving(info.offers, offer) : 0;
                return (
                  <button
                    key={offer.id}
                    type="button"
                    className={offer.period === 'year' ? 'primary' : 'secondary'}
                    disabled={busy !== null}
                    onClick={() => buy(offer)}
                  >
                    {offer.period === 'year' ? t.offerYear(offer.stars) : t.offerMonth(offer.stars)}
                    {saving > 0 && <span className="plan-saving">{t.offerYearSave(saving)}</span>}
                  </button>
                );
              })}
            </div>
          </div>
        );
      })}

      {info.offers.length > 0 && <p className="field-hint plan-hint">{t.planStarsHint}</p>}
    </section>
  );
}
