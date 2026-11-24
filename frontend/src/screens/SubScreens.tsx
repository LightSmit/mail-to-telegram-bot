import { t } from '../i18n'
import { GroupPanel } from '../GroupPanel'
import { PasswordPanel } from '../PasswordPanel'
import type { Account } from '../types'
import { ScreenHeader } from '../ui'

// Групповой чат ящика (Business): прежняя панель в новом экране
export function GroupScreen({ account, business, createLink, unlink, onUpgrade, onBack }: {
  account: Account;
  business: boolean;
  createLink: () => Promise<string | null>;
  unlink: () => Promise<boolean>;
  onUpgrade?: () => void;
  onBack: () => void;
}) {
  return (
    <div className="screen">
      <ScreenHeader title={t.groupTitle} subtitle={account.email} onBack={onBack} />
      <div className="group-card panel-card">
        <GroupPanel
          groupTitle={account.groupTitle}
          linked={account.groupLinked === true}
          business={business}
          createLink={createLink}
          unlink={unlink}
          onUpgrade={onUpgrade}
        />
      </div>
    </div>
  );
}

// Новый пароль приложения для подключенного ящика
export function PasswordScreen({ account, onSave, onBack }: {
  account: Account;
  onSave: (password: string) => Promise<boolean>;
  onBack: () => void;
}) {
  return (
    <div className="screen">
      <ScreenHeader title={t.appPasswordLabel} subtitle={account.email} onBack={onBack} />
      <div className="group-card panel-card">
        <PasswordPanel host={account.host} email={account.email} broken={account.status === 'ERROR'} onSave={onSave} />
      </div>
    </div>
  );
}
