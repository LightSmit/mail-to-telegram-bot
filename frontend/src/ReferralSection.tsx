import { useState } from 'react'
import { t } from './i18n'
import { openTelegramLink } from './telegram'

export interface ReferralInfo {
  enabled: boolean;
  link?: string | null;
  invited: number;
  qualified: number;
  goal: number;
  towardsNext: number;
  rewards: number;
  shareText: string;
}

// Приглашение друзей: своя ссылка, прогресс до следующего месяца Pro и кнопки «Поделиться» / «Скопировать»
export function ReferralSection({ info }: { info: ReferralInfo }) {
  const [copied, setCopied] = useState(false);
  if (!info.enabled || !info.link) return null;
  const link = info.link;

  const share = () => {
    const url = `https://t.me/share/url?url=${encodeURIComponent(link)}&text=${encodeURIComponent(info.shareText)}`;
    openTelegramLink(url);
  };

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // буфер обмена недоступен: ссылку можно выделить вручную
    }
  };

  const percent = info.goal > 0 ? Math.round((info.towardsNext / info.goal) * 100) : 0;

  return (
    <section className="section" aria-labelledby="referral-title">
      <h2 id="referral-title" className="section-title">{t.referralTitle}</h2>
      <div className="plan-offer referral">
        <p className="referral-lede">{t.referralLede(info.goal)}</p>

        <div className="referral-progress" role="progressbar" aria-valuemin={0} aria-valuemax={info.goal} aria-valuenow={info.towardsNext}>
          <div className="referral-bar"><span style={{ width: `${percent}%` }} /></div>
          <span className="referral-count">{t.referralCount(info.towardsNext, info.goal)}</span>
        </div>

        <code className="referral-link">{link}</code>

        <div className="plan-buttons">
          <button type="button" className="primary" onClick={share}>{t.referralShare}</button>
          <button type="button" className="secondary" onClick={copy}>{copied ? t.referralCopied : t.referralCopy}</button>
        </div>

        <p className="field-hint">
          {t.referralStats(info.invited, info.qualified)}
          {info.rewards > 0 && ` · ${t.referralRewards(info.rewards)}`}
        </p>
        <p className="field-hint">{t.referralRule}</p>
      </div>
    </section>
  );
}
