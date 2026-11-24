import { useState } from 'react'
import { t } from '../i18n'
import type { Offer, PlanId, PlanInfo } from '../PlanSection'
import type { ReferralInfo } from '../ReferralSection'
import { openInvoice, openTelegramLink } from '../telegram'
import type { Notify } from '../types'
import { Group, Icon, Row, ScreenHeader, SwitchRow } from '../ui'

interface Props {
  info: PlanInfo | null;
  referral: ReferralInfo | null;
  createInvoice: (offerId: string) => Promise<string | null>;
  onPaid: () => Promise<void>;
  startTrial: () => Promise<boolean>;
  // Выключить или включить автопродление месячной подписки
  setAutoRenew: (enabled: boolean) => Promise<boolean>;
  notify: Notify;
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

export function PlanScreen({ info, referral, createInvoice, onPaid, startTrial, setAutoRenew, notify }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  // Подтверждение перед выключением автопродления
  const [confirmOff, setConfirmOff] = useState(false);
  const [selected, setSelected] = useState<PlanId>(info?.plan === 'PRO' ? 'BUSINESS' : 'PRO');
  const [copied, setCopied] = useState(false);

  if (!info) {
    return (
      <div className="screen">
        <ScreenHeader large title={t.tabPlan} />
        <p className="empty">{t.loading}</p>
      </div>
    );
  }

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

  const tryPro = async () => {
    setBusy('trial');
    try {
      await startTrial();
    } finally {
      setBusy(null);
    }
  };

  const toggleRenew = async (enabled: boolean) => {
    setBusy('renew');
    try {
      if (await setAutoRenew(enabled)) setConfirmOff(false);
    } finally {
      setBusy(null);
    }
  };

  const renewable = (info.recurring || info.renewalCancelled === true) && Boolean(info.expiresAt);
  const endDate = info.expiresAt ? formatDate(info.expiresAt) : '';

  const plans = (['PRO', 'BUSINESS'] as PlanId[]).filter((plan) => info.offers.some((offer) => offer.plan === plan));
  const shown = plans.includes(selected) ? selected : plans[0];
  const offers = info.offers.filter((offer) => offer.plan === shown);
  const perks = shown === 'BUSINESS' ? t.businessPerks : t.proPerks;

  const share = () => {
    if (!referral?.link) return;
    openTelegramLink(`https://t.me/share/url?url=${encodeURIComponent(referral.link)}&text=${encodeURIComponent(referral.shareText)}`);
  };

  const copyLink = async () => {
    if (!referral?.link) return;
    try {
      await navigator.clipboard.writeText(referral.link);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // буфер обмена недоступен
    }
  };

  const goal = referral?.goal ?? 0;
  const progress = goal > 0 ? Math.min(1, (referral?.towardsNext ?? 0) / goal) : 0;
  const circumference = 2 * Math.PI * 23;

  return (
    <div className="screen">
      <ScreenHeader large title={t.tabPlan} />

      <div className={`plan-hero plan-hero-${info.plan.toLowerCase()}`}>
        <span className="plan-hero-label">{t.planNow}</span>
        <span className="plan-hero-name">{t.planName(info.plan)}</span>
        <span className="plan-hero-status">{statusLine(info)}</span>
        {info.plan !== 'FREE' && <span className="plan-hero-status">{t.planMailboxes(info.maxMailboxes)}</span>}
      </div>

      {renewable && (
        <Group footer={confirmOff ? t.autoRenewAskText(endDate) : info.recurring ? t.autoRenewOnHint(endDate) : t.autoRenewOffHint(endDate)}>
          {confirmOff ? (
            <>
              <Row title={busy === 'renew' ? t.loading : t.autoRenewTurnOff} danger onClick={busy ? undefined : () => toggleRenew(false)} />
              <Row title={t.cancel} accent onClick={() => setConfirmOff(false)} />
            </>
          ) : (
            <SwitchRow
              icon="sparkle"
              iconBg="#5A4FB3"
              title={t.autoRenew}
              checked={info.recurring}
              disabled={busy !== null}
              // Выключение - с подтверждением, включение - сразу
              onChange={(value) => (value ? toggleRenew(true) : setConfirmOff(true))}
            />
          )}
        </Group>
      )}

      {plans.length > 0 && (
        <>
          {plans.length > 1 && (
            <div className="segmented" role="tablist">
              {plans.map((plan) => (
                <button key={plan} type="button" role="tab" aria-selected={shown === plan} onClick={() => setSelected(plan)}>
                  {t.planName(plan)}
                </button>
              ))}
            </div>
          )}
          <div className="offer-card">
            <ul className="perks">
              {perks.map((perk) => <li key={perk}><Icon name="check" size={16} />{perk}</li>)}
            </ul>
            <div className="offer-buttons">
              {offers.map((offer) => {
                const saving = offer.period === 'year' ? yearSaving(info.offers, offer) : 0;
                return (
                  <button
                    key={offer.id}
                    type="button"
                    className={offer.period === 'month' ? 'offer primary' : 'offer tinted'}
                    disabled={busy !== null}
                    onClick={() => buy(offer)}
                  >
                    <span className="offer-period">
                      {offer.period === 'year' ? t.offerYear(offer.stars).split('·')[0] : t.offerMonth(offer.stars).split('·')[0]}
                      {saving > 0 && ` · −${saving}%`}
                    </span>
                    <span className="offer-price">{offer.stars} ⭐</span>
                  </button>
                );
              })}
            </div>
            {info.trialAvailable && info.trialDays ? (
              <button type="button" className="trial-button" disabled={busy !== null} onClick={tryPro}>
                <span className="trial-title">{busy === 'trial' ? t.trialStarting : t.trialTitle(info.trialDays)}</span>
                <span className="trial-note"><Icon name="check" size={14} /> {t.trialNoRenew}</span>
              </button>
            ) : null}
          </div>
          <p className="group-footer">{t.planStarsHint}</p>
        </>
      )}

      {referral?.enabled && referral.link && (
        <section className="group">
          <h2 className="group-title">{t.referralTitle}</h2>
          <div className="group-card referral-card">
            <div className="referral-row">
              <svg width="56" height="56" viewBox="0 0 56 56" role="img" aria-label={t.referralCount(referral.towardsNext, goal)}>
                <circle cx="28" cy="28" r="23" fill="none" className="ring-track" strokeWidth="6" />
                <circle
                  cx="28" cy="28" r="23" fill="none" className="ring-value" strokeWidth="6" strokeLinecap="round"
                  strokeDasharray={`${circumference * progress} ${circumference}`} transform="rotate(-90 28 28)"
                />
                <text x="28" y="33" textAnchor="middle" className="ring-text">{referral.towardsNext}/{goal}</text>
              </svg>
              <span className="row-text">
                <span className="row-title">{t.referralLede(goal)}</span>
                <span className="row-subtitle">{t.referralRule}</span>
              </span>
            </div>
            <div className="offer-buttons">
              <button type="button" className="primary" onClick={share}>{t.referralShare}</button>
              <button type="button" className="tinted" onClick={copyLink}>{copied ? t.referralCopied : t.referralCopy}</button>
            </div>
          </div>
          <p className="group-footer">
            {t.referralStats(referral.invited, referral.qualified)}
            {referral.rewards > 0 && ` · ${t.referralRewards(referral.rewards)}`}
          </p>
        </section>
      )}
    </div>
  );
}
