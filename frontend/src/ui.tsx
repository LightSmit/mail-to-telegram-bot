import { useMemo, type ReactNode } from 'react'
import { t } from './i18n'
import { telegramScriptLoaded } from './telegram'

// Общие элементы нового интерфейса: экран, группа строк, строка, переключатель, иконки

/** Заголовок экрана. Без Telegram показываем свою кнопку «Назад» (в Telegram она в шапке). */
export function ScreenHeader({ title, subtitle, onBack, large = false, right }: {
  title: string;
  subtitle?: ReactNode;
  onBack?: () => void;
  large?: boolean;
  right?: ReactNode;
}) {
  return (
    <header className="screen-header">
      {onBack && !telegramScriptLoaded && (
        <button type="button" className="back-link" onClick={onBack}>
          <Icon name="chevron-left" size={16} /> {t.back}
        </button>
      )}
      <div className="screen-title-row">
        <div className="screen-titles">
          <h1 className={large ? 'screen-title large' : 'screen-title'}>{title}</h1>
          {subtitle && <p className="screen-subtitle">{subtitle}</p>}
        </div>
        {right}
      </div>
    </header>
  );
}

/** Группа строк в стиле настроек Telegram: заголовок, карточка, подпись. */
export function Group({ title, footer, children, className }: {
  title?: string;
  footer?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={className ? `group ${className}` : 'group'}>
      {title && <h2 className="group-title">{title}</h2>}
      <div className="group-card">{children}</div>
      {footer && <p className="group-footer">{footer}</p>}
    </section>
  );
}

/** Строка списка: иконка, текст, значение справа и стрелка. С onClick - кнопка. */
export function Row({ icon, iconBg, title, subtitle, value, onClick, chevron, danger, accent, children }: {
  icon?: IconName;
  iconBg?: string;
  title: ReactNode;
  subtitle?: ReactNode;
  value?: ReactNode;
  onClick?: () => void;
  chevron?: boolean;
  danger?: boolean;
  accent?: boolean;
  children?: ReactNode;
}) {
  const content = (
    <>
      {icon && (
        <span className="row-icon" style={{ background: iconBg }} aria-hidden="true">
          <Icon name={icon} size={16} />
        </span>
      )}
      <span className="row-text">
        <span className="row-title">{title}</span>
        {subtitle && <span className="row-subtitle">{subtitle}</span>}
      </span>
      {value !== undefined && <span className="row-value">{value}</span>}
      {children}
      {chevron && <Icon name="chevron-right" size={14} className="row-chevron" />}
    </>
  );
  const className = ['row', danger ? 'row-danger' : '', accent ? 'row-accent' : ''].filter(Boolean).join(' ');
  return onClick
    ? <button type="button" className={className} onClick={onClick}>{content}</button>
    : <div className={className}>{content}</div>;
}

/** Переключатель iOS/Telegram. */
export function Switch({ checked, onChange, label, disabled }: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      className="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className="switch-knob" />
    </button>
  );
}

/** Строка с переключателем. */
export function SwitchRow({ icon, iconBg, title, subtitle, checked, onChange, disabled }: {
  icon?: IconName;
  iconBg?: string;
  title: string;
  subtitle?: ReactNode;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <Row icon={icon} iconBg={iconBg} title={title} subtitle={subtitle}>
      <Switch checked={checked} onChange={onChange} label={title} disabled={disabled} />
    </Row>
  );
}

export function Chip({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" className="chip" aria-pressed={selected} onClick={onClick}>{children}</button>
  );
}

const AVATAR_COLORS = ['#2F6FB5', '#B5573B', '#2E7D4F', '#5A4FB3', '#8A6A2B', '#2E6F7D', '#9C3F6B'];

/** Круглая аватарка с буквой; цвет постоянный для одного и того же текста. */
export function Avatar({ text, size = 40 }: { text: string; size?: number }) {
  const color = useMemo(() => {
    let hash = 0;
    for (const char of text) hash = (hash * 31 + char.charCodeAt(0)) | 0;
    return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
  }, [text]);
  const letter = (text.trim().charAt(0) || '@').toUpperCase();
  return (
    <span className="avatar" style={{ width: size, height: size, background: color, fontSize: size * 0.4 }} aria-hidden="true">
      {letter}
    </span>
  );
}

export type Tone = 'ok' | 'warn' | 'error' | 'muted';

export function StatusLine({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className={`status-line tone-${tone}`}>
      <span className="status-dot" aria-hidden="true" />
      {children}
    </span>
  );
}

export function Notice({ kind, text, onClose }: { kind: 'info' | 'ok' | 'error'; text: string; onClose: () => void }) {
  if (!text) return null;
  return (
    <div className={`toast toast-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <span>{text}</span>
      <button type="button" className="toast-close" aria-label={t.close} onClick={onClose}>
        <Icon name="close" size={14} />
      </button>
    </div>
  );
}

export type IconName =
  | 'mail' | 'star' | 'settings' | 'shield' | 'chevron-left' | 'chevron-right' | 'plus' | 'close' | 'copy'
  | 'pause' | 'list' | 'filter' | 'group' | 'lock' | 'send' | 'check' | 'key' | 'sparkle' | 'globe' | 'trash' | 'external';

const PATHS: Record<IconName, ReactNode> = {
  'mail': <><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M4 7l8 6 8-6" /></>,
  'star': <path d="M12 3l2.6 5.6 6.1.7-4.5 4.2 1.2 6L12 16.6 6.6 19.5l1.2-6-4.5-4.2 6.1-.7L12 3z" />,
  'settings': <><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1" /></>,
  'shield': <path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6l8-3z" />,
  'chevron-left': <path d="M15 5l-7 7 7 7" />,
  'chevron-right': <path d="M9 5l7 7-7 7" />,
  'plus': <path d="M12 5v14M5 12h14" />,
  'close': <path d="M6 6l12 12M18 6L6 18" />,
  'copy': <><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M16 8V6a2 2 0 00-2-2H6a2 2 0 00-2 2v8a2 2 0 002 2h2" /></>,
  'pause': <path d="M9 6v12M15 6v12" />,
  'list': <path d="M5 7h14M8 12h8M10 17h4" />,
  'filter': <path d="M4 5h16l-6 7v6l-4-2v-4L4 5z" />,
  'group': <><circle cx="9" cy="9" r="3" /><circle cx="17" cy="10" r="2.5" /><path d="M3 19c.7-3 3-4.5 6-4.5s5.3 1.5 6 4.5M15 15c2.5 0 4.3 1.2 5 3.5" /></>,
  'lock': <><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 018 0v3" /></>,
  'send': <path d="M3 11l18-8-7 18-3-7-8-3z" />,
  'check': <path d="M5 12.5l4.5 4.5L19 7" />,
  'key': <><circle cx="8" cy="15" r="4" /><path d="M11 12l8-8M16 7l2 2" /></>,
  'sparkle': <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6" />,
  'globe': <><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9S9.5 5.5 12 3z" /></>,
  'trash': <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13" />,
  'external': <path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 01-1 1H5a1 1 0 01-1-1V7a1 1 0 011-1h5" />,
};

export function Icon({ name, size = 20, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.9}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {PATHS[name]}
    </svg>
  );
}
