import { t } from '../i18n'
import type { PlanInfo } from '../PlanSection'
import { displayName, statusLabel, statusTone, type Account } from '../types'
import { Avatar, Group, Icon, ScreenHeader, StatusLine } from '../ui'

// Первый запуск: ни одного ящика - рассказываем, что умеет бот, и ведем к подключению
export function Onboarding({ onStart }: { onStart: () => void }) {
  const features = [
    { icon: 'lock' as const, tone: 'green', title: t.onbF1Title, text: t.onbF1Text },
    { icon: 'filter' as const, tone: 'blue', title: t.onbF2Title, text: t.onbF2Text },
    { icon: 'key' as const, tone: 'violet', title: t.onbF3Title, text: t.onbF3Text },
  ];
  return (
    <div className="screen onboarding">
      <div className="onb-hero" aria-hidden="true">
        <svg width="200" height="140" viewBox="0 0 200 140" fill="none">
          <rect x="8" y="30" width="96" height="70" rx="12" className="onb-envelope" />
          <path d="M14 38l42 32 42-32" className="onb-envelope-flap" />
          <path d="M112 66h22" className="onb-dash" />
          <circle cx="160" cy="66" r="34" className="onb-plane-bg" />
          <path d="M144 66l30-13-6 29-9-8-6 7v-9l13-12" className="onb-plane" />
        </svg>
      </div>
      <div className="onb-text">
        <h1 className="screen-title large">{t.onbTitle}</h1>
        <p className="onb-lede">{t.onbLede}</p>
      </div>
      <ul className="onb-features">
        {features.map((feature) => (
          <li key={feature.title}>
            <span className={`onb-icon tone-bg-${feature.tone}`}><Icon name={feature.icon} size={20} /></span>
            <span className="onb-feature-text">
              <span className="onb-feature-title">{feature.title}</span>
              <span className="onb-feature-hint">{feature.text}</span>
            </span>
          </li>
        ))}
      </ul>
      <div className="bottom-actions">
        <button type="button" className="primary big" onClick={onStart}>{t.onbCta}</button>
        <p className="bottom-note">{t.onbNote}</p>
      </div>
    </div>
  );
}

interface HomeProps {
  accounts: Account[];
  loading: boolean;
  plan: PlanInfo | null;
  onOpen: (account: Account) => void;
  onAdd: () => void;
  onPlan: () => void;
  onSearch: () => void;
}

export function Home({ accounts, loading, plan, onOpen, onAdd, onPlan, onSearch }: HomeProps) {
  // Поиск по всем ящикам имеет смысл, когда есть хотя бы один ящик с входом в почту
  const searchable = accounts.some((account) => !account.forwardAddress);
  const limit = plan?.maxMailboxes;
  return (
    <div className="screen">
      <ScreenHeader
        large
        title={t.tabMailboxes}
        subtitle={t.homeLede}
        right={plan && plan.billingEnabled && (
          <button type="button" className={`plan-pill plan-pill-${plan.plan.toLowerCase()}`} onClick={onPlan}>
            {t.planName(plan.plan)}
          </button>
        )}
      />

      {!loading && searchable && (
        <button type="button" className="search-field search-bar" onClick={onSearch}>
          <Icon name="search" size={18} />
          <span className="search-bar-text">{t.searchAll}</span>
          {plan && plan.billingEnabled && plan.plan === 'FREE' && <span className="pro-badge">{t.proBadge}</span>}
        </button>
      )}

      {loading ? (
        <p className="empty">{t.loadingMailboxes}</p>
      ) : (
        <Group
          title={t.connectedTitle}
          footer={limit ? t.mailboxesFooter(limit) : undefined}
        >
          {accounts.map((account) => {
            const tone = statusTone(account);
            return (
              <button key={account.id} type="button" className="row mailbox-row" onClick={() => onOpen(account)}>
                <Avatar text={displayName(account)} />
                <span className="row-text">
                  <span className="row-title">
                    {displayName(account)}
                    {account.forwardAddress && <span className="badge">{t.forwardBadge}</span>}
                  </span>
                  {displayName(account) !== account.email && <span className="row-subtitle">{account.email}</span>}
                  <StatusLine tone={tone}>{statusLabel(account)}</StatusLine>
                </span>
                {account.status === 'ERROR' && !account.forwardAddress
                  ? <span className="fix-pill">{t.fix}</span>
                  : <Icon name="chevron-right" size={14} className="row-chevron" />}
              </button>
            );
          })}
          <button type="button" className="row row-accent" onClick={onAdd}>
            <span className="avatar avatar-add" aria-hidden="true"><Icon name="plus" size={18} /></span>
            <span className="row-text"><span className="row-title">{t.connect}</span></span>
          </button>
        </Group>
      )}
    </div>
  );
}
