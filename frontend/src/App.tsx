import { useCallback, useEffect, useRef, useState } from 'react'
import './App.css'
import { initData, showBackButton } from './telegram'
import { lang, rememberLang, t, type Lang } from './i18n'
import { LOGIN_TIMEOUT_MS, describeError, errorText, request, sleep } from './api'
import { withDefaults, type MailFilters } from './FiltersPanel'
import type { NotificationSettings } from './NotificationsSection'
import { MailboxView } from './MailboxView'
import { hasPro, type PlanInfo } from './PlanSection'
import type { ReferralInfo } from './ReferralSection'
import { Sky, loadSkyEnabled, saveSkyEnabled } from './Sky'
import type { Account, Forwarding, NoticeKind } from './types'
import { Icon, Notice } from './ui'
import { Home, Onboarding } from './screens/Home'
import { AddScreen, type AddMode } from './screens/Add'
import { AddPassword, type PasswordForm } from './screens/AddPassword'
import { ForwardSetup } from './screens/ForwardSetup'
import { MailboxScreen } from './screens/Mailbox'
import { FiltersScreen } from './screens/Filters'
import { GroupScreen, PasswordScreen } from './screens/SubScreens'
import { PlanScreen } from './screens/Plan'
import { SearchScreen } from './screens/Search'
import { AdminBroadcastScreen, AdminTab, AdminUserScreen, type AdminSection, type UserFilter } from './screens/Admin'
import { SettingsScreen } from './screens/Settings'

type Tab = 'home' | 'plan' | 'settings' | 'admin';

// Экраны поверх вкладок: открываются по нажатию, «Назад» возвращает к предыдущему
type Route =
  | { name: 'add' }
  | { name: 'addPassword'; email: string; providerId: string }
  | { name: 'forward'; id: number }
  | { name: 'mailbox'; id: number }
  | { name: 'filters'; id: number }
  | { name: 'group'; id: number }
  | { name: 'password'; id: number }
  | { name: 'mail'; id: number; q?: string }
  | { name: 'search' }
  | { name: 'adminUser'; id: number }
  | { name: 'adminBroadcast' };

// Пока приложение открыто, статусы ящиков обновляются сами
const REFRESH_INTERVAL_MS = 15000;
// После оплаты сервер узнает о ней из сообщения Telegram: проверяем тариф несколько раз
const PLAN_POLL_ATTEMPTS = 6;
const PLAN_POLL_DELAY_MS = 1500;
// Сообщения об успехе исчезают сами, об ошибке - висят, пока их не закроют
const NOTICE_HIDE_MS = 4500;

function App() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  // Список ящиков хоть раз загрузился: без этого при ошибке связи показали бы экран первого запуска
  const [loaded, setLoaded] = useState(false);
  const [plan, setPlan] = useState<PlanInfo | null>(null);
  const [referral, setReferral] = useState<ReferralInfo | null>(null);
  const [forwarding, setForwarding] = useState<Forwarding | null>(null);
  const [notice, setNotice] = useState<{ kind: NoticeKind; text: string } | null>(
    initData ? null : { kind: 'error', text: t.openFromTelegram },
  );
  const [tab, setTab] = useState<Tab>('home');
  const [stack, setStack] = useState<Route[]>([]);
  const [busy, setBusy] = useState(false);
  const [sky, setSky] = useState(loadSkyEnabled);
  // Админ-панель: раздел и фильтр людей сохраняются при переходах
  const [adminSection, setAdminSection] = useState<AdminSection>('overview');
  const [adminFilter, setAdminFilter] = useState<UserFilter>('all');
  const mainRef = useRef<HTMLElement>(null);

  const notify = useCallback((kind: NoticeKind, text: string) => setNotice(text ? { kind, text } : null), []);

  useEffect(() => {
    if (!notice || notice.kind === 'error') return;
    const timer = setTimeout(() => setNotice(null), NOTICE_HIDE_MS);
    return () => clearTimeout(timer);
  }, [notice]);

  const push = (route: Route) => setStack((current) => [...current, route]);
  const pop = useCallback(() => setStack((current) => current.slice(0, -1)), []);
  const reset = (routes: Route[] = []) => setStack(routes);
  const top = stack[stack.length - 1];

  // Новый экран - с начала страницы
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [stack.length, tab]);

  // Системная кнопка «Назад» Telegram; письма управляют ею сами
  useEffect(() => {
    // Просмотр писем и поиск сами ведут кнопку «Назад»: из письма - к списку
    if (!top || top.name === 'mail' || top.name === 'search') return;
    return showBackButton(pop);
  }, [top, pop]);

  const fetchAccounts = async (silent = false) => {
    try {
      const response = await request('/accounts');
      if (response.ok) {
        setAccounts(await response.json());
        setLoaded(true);
      } else if (!silent) {
        notify('error', await errorText(response, t.loadMailboxesFailed));
      }
    } catch (error) {
      if (!silent) notify('error', describeError(error));
    } finally {
      setLoading(false);
    }
  };

  const fetchPlan = async (): Promise<PlanInfo | null> => {
    try {
      const response = await request('/plan');
      if (!response.ok) return null;
      const info: PlanInfo = await response.json();
      setPlan(info);
      return info;
    } catch {
      return null;
    }
  };

  const fetchJson = async <T,>(path: string, set: (value: T) => void) => {
    try {
      const response = await request(path);
      if (response.ok) set(await response.json());
    } catch {
      // без связи раздел просто не показываем
    }
  };

  useEffect(() => {
    fetchAccounts();
    fetchPlan();
    fetchJson<ReferralInfo>('/referral', setReferral);
    fetchJson<Forwarding>('/forwarding', setForwarding);
    const timer = setInterval(() => fetchAccounts(true), REFRESH_INTERVAL_MS);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Язык мог быть выбран в боте (/language) или на другом устройстве: сверяемся с сервером один раз
  useEffect(() => {
    if (!initData) return;
    (async () => {
      try {
        const response = await request('/language');
        if (!response.ok) return;
        const { language } = await response.json();
        // Перезагружаемся, только если язык удалось запомнить: иначе была бы бесконечная перезагрузка
        if ((language === 'ru' || language === 'en') && language !== lang && rememberLang(language)) {
          window.location.reload();
        }
      } catch {
        // без связи остаемся на текущем языке
      }
    })();
  }, []);

  const withNotice = async (action: () => Promise<Response>, failPrefix: string, onOk: (response: Response) => Promise<void> | void): Promise<boolean> => {
    try {
      const response = await action();
      if (response.ok) {
        await onOk(response);
        return true;
      }
      notify('error', await errorText(response, failPrefix));
    } catch (error) {
      notify('error', describeError(error));
    }
    return false;
  };

  const showPlans = () => {
    reset();
    setTab('plan');
  };

  // Подключение

  const continueAdd = async (mode: AddMode, email: string, providerId: string) => {
    if (mode === 'password') {
      push({ name: 'addPassword', email, providerId });
      return;
    }
    setBusy(true);
    await withNotice(() => request('/accounts/forward', 'POST', { name: '', email }), t.addFailed, async (response) => {
      const added: Account = await response.json();
      await fetchAccounts(true);
      reset([{ name: 'forward', id: added.id }]);
    });
    setBusy(false);
  };

  const addWithPassword = async (form: PasswordForm) => {
    setBusy(true);
    notify('info', t.checkingLogin);
    await withNotice(
      () => request('/accounts', 'POST', { name: form.name, host: form.host, port: 993, username: form.email, appPassword: form.password }, LOGIN_TIMEOUT_MS),
      t.addFailed,
      async () => {
        notify('ok', t.mailboxAdded);
        await fetchAccounts(true);
        reset();
      },
    );
    setBusy(false);
  };

  // Ящик

  const saveFilters = (account: Account, filters: MailFilters) =>
    withNotice(() => request(`/accounts/${account.id}/filters`, 'PUT', filters), t.filtersFailed, async (response) => {
      const saved = withDefaults(await response.json());
      setAccounts((current) => current.map((item) => (item.id === account.id ? { ...item, filters: saved } : item)));
    });

  const testDelivery = (account: Account) =>
    withNotice(() => request(`/accounts/${account.id}/test`, 'POST'), t.deliveryTestFailed, () => {
      notify('ok', t.deliveryTestSent);
    });

  const updatePassword = (account: Account, password: string) => {
    notify('info', t.checkingPassword(account.email));
    return withNotice(
      () => request(`/accounts/${account.id}/password`, 'PUT', { appPassword: password }, LOGIN_TIMEOUT_MS),
      t.passwordFailed,
      async () => {
        notify('ok', t.passwordUpdated(account.email));
        await fetchAccounts(true);
        pop();
      },
    );
  };

  const deleteAccount = (account: Account) =>
    withNotice(() => request(`/accounts/${account.id}`, 'DELETE'), t.removeFailed, async () => {
      notify('ok', t.mailboxRemoved(account.email));
      reset();
      await fetchAccounts(true);
    });

  const createGroupLink = async (account: Account): Promise<string | null> => {
    let link: string | null = null;
    await withNotice(() => request(`/accounts/${account.id}/group`, 'POST'), t.groupLinkFailed, async (response) => {
      link = (await response.json()).link;
    });
    return link;
  };

  const unlinkGroup = (account: Account) =>
    withNotice(() => request(`/accounts/${account.id}/group`, 'DELETE'), t.groupUnlinkFailed, async () => {
      notify('ok', t.groupUnlinked);
      await fetchAccounts(true);
    });

  // Тариф

  const refreshAfterPayment = async () => {
    const before = plan;
    for (let attempt = 0; attempt < PLAN_POLL_ATTEMPTS; attempt++) {
      const info = await fetchPlan();
      if (info && (info.plan !== before?.plan || info.expiresAt !== before?.expiresAt)) break;
      await sleep(PLAN_POLL_DELAY_MS);
    }
    await fetchAccounts(true);
  };

  const createInvoice = async (offerId: string): Promise<string | null> => {
    let link: string | null = null;
    await withNotice(() => request('/plan/invoice', 'POST', { offer: offerId }), t.paymentFailed, async (response) => {
      link = (await response.json()).link;
    });
    return link;
  };

  const startTrial = async () => {
    notify('info', t.trialStarting);
    return withNotice(() => request('/plan/trial', 'POST'), t.trialFailed, async (response) => {
      setPlan(await response.json());
      notify('ok', t.trialStarted);
      await fetchAccounts(true);
    });
  };

  const setAutoRenew = (enabled: boolean) =>
    withNotice(() => request('/plan/autorenew', 'PUT', { enabled }), t.autoRenewFailed, async (response) => {
      setPlan(await response.json());
      notify('ok', enabled ? t.autoRenewOn : t.autoRenewOff);
    });

  // Настройки

  const loadSettings = async (): Promise<NotificationSettings | null> => {
    try {
      const response = await request('/settings');
      return response.ok ? await response.json() : null;
    } catch {
      return null;
    }
  };

  const saveSettings = async (settings: NotificationSettings): Promise<NotificationSettings | null> => {
    let saved: NotificationSettings | null = null;
    await withNotice(() => request('/settings', 'PUT', settings), t.notifyFailed, async (response) => {
      notify('ok', t.notifySaved);
      saved = await response.json();
    });
    return saved;
  };

  const saveLanguage = (value: Lang) => withNotice(() => request('/language', 'PUT', { language: value }), t.noServer, () => undefined);

  const deleteAll = () =>
    withNotice(() => request('/me', 'DELETE'), t.dataDeleteFailed, () => {
      setAccounts([]);
      notify('ok', t.dataDeleted);
    });

  const toggleSky = (enabled: boolean) => {
    setSky(enabled);
    saveSkyEnabled(enabled);
  };

  // Отрисовка

  const accountOf = (id: number) => accounts.find((account) => account.id === id);
  const pro = hasPro(plan);
  const business = plan === null || plan.plan === 'BUSINESS';
  const upgrade = plan?.billingEnabled ? showPlans : undefined;
  const forwardingEnabled = forwarding?.enabled === true;

  const renderRoute = (route: Route) => {
    switch (route.name) {
      case 'adminUser':
        return <AdminUserScreen key={route.id} userId={route.id} notify={notify} onBack={pop} />;
      case 'adminBroadcast':
        return <AdminBroadcastScreen notify={notify} onBack={pop} />;
      case 'search':
        return (
          <SearchScreen
            request={request}
            pro={pro}
            mailboxes={accounts.filter((account) => !account.forwardAddress)}
            hasForwardMailboxes={accounts.some((account) => Boolean(account.forwardAddress))}
            onUpgrade={upgrade}
            onOpenMailbox={(id, q) => push({ name: 'mail', id, q })}
            onBack={pop}
          />
        );
      case 'add':
        return (
          <AddScreen
            forwardingEnabled={forwardingEnabled}
            busy={busy}
            onBack={pop}
            onProvider={(detail) => request('/funnel', 'POST', { step: 'provider', detail }).catch(() => undefined)}
            onContinue={continueAdd}
          />
        );
      case 'addPassword':
        return (
          <AddPassword
            email={route.email}
            initialProviderId={route.providerId}
            forwardingEnabled={forwardingEnabled}
            busy={busy}
            onBack={pop}
            onSubmit={addWithPassword}
            onSwitchToForward={() => continueAdd('forward', route.email, route.providerId)}
          />
        );
    }

    const account = accountOf(route.id);
    if (!account) return <p className="empty">{loading ? t.loadingMailboxes : t.loadMailboxesFailed}</p>;

    switch (route.name) {
      case 'forward':
        return <ForwardSetup account={account} onBack={pop} onDone={() => reset()} />;
      case 'mailbox':
        return (
          <MailboxScreen
            account={account}
            onBack={pop}
            onOpenMails={() => push({ name: 'mail', id: account.id })}
            onFilters={() => push({ name: 'filters', id: account.id })}
            onGroup={() => push({ name: 'group', id: account.id })}
            onPassword={() => push({ name: 'password', id: account.id })}
            onForward={() => push({ name: 'forward', id: account.id })}
            onSaveFilters={(filters) => saveFilters(account, filters)}
            onTest={() => testDelivery(account)}
            onDelete={() => deleteAccount(account)}
          />
        );
      case 'filters':
        return (
          <FiltersScreen
            account={account}
            pro={pro}
            onUpgrade={upgrade}
            onBack={pop}
            onSave={async (filters) => {
              const ok = await saveFilters(account, filters);
              if (ok) notify('ok', t.filtersSaved(account.email));
              return ok;
            }}
          />
        );
      case 'group':
        return (
          <GroupScreen
            account={account}
            business={business}
            createLink={() => createGroupLink(account)}
            unlink={() => unlinkGroup(account)}
            onUpgrade={upgrade}
            onBack={pop}
          />
        );
      case 'password':
        return <PasswordScreen account={account} onSave={(password) => updatePassword(account, password)} onBack={pop} />;
      case 'mail':
        return (
          <MailboxView
            account={account}
            request={request}
            limited={!pro}
            onUpgrade={upgrade}
            onClose={pop}
            initialQuery={route.q}
          />
        );
    }
  };

  const onboarding = tab === 'home' && !top && loaded && accounts.length === 0 && Boolean(initData);

  const renderTab = () => {
    switch (tab) {
      case 'home':
        return onboarding
          ? <Onboarding onStart={() => push({ name: 'add' })} />
          : (
            <Home
              accounts={accounts}
              loading={loading}
              plan={plan}
              onOpen={(account) => push({ name: 'mailbox', id: account.id })}
              onAdd={() => push({ name: 'add' })}
              onPlan={showPlans}
              onSearch={() => push({ name: 'search' })}
            />
          );
      case 'plan':
        return (
          <PlanScreen
            info={plan}
            referral={referral}
            createInvoice={createInvoice}
            onPaid={refreshAfterPayment}
            startTrial={startTrial}
            setAutoRenew={setAutoRenew}
            notify={notify}
          />
        );
      case 'admin':
        return (
          <AdminTab
            section={adminSection}
            onSection={setAdminSection}
            filter={adminFilter}
            onFilter={setAdminFilter}
            onOpenUser={(id) => push({ name: 'adminUser', id })}
            onBroadcast={() => push({ name: 'adminBroadcast' })}
            notify={notify}
          />
        );
      case 'settings':
        return (
          <SettingsScreen
            load={loadSettings}
            save={saveSettings}
            saveLanguage={saveLanguage}
            sky={sky}
            onSky={toggleSky}
            onDeleteAll={deleteAll}
          />
        );
    }
  };

  const tabs: { id: Tab; icon: 'mail' | 'star' | 'settings' | 'shield'; label: string }[] = [
    { id: 'home', icon: 'mail', label: t.tabMailboxes },
    { id: 'plan', icon: 'star', label: t.tabPlan },
    { id: 'settings', icon: 'settings', label: t.tabSettings },
    // Только админам из .env (сервер проверяет каждый запрос)
    ...(plan?.admin ? [{ id: 'admin' as const, icon: 'shield' as const, label: 'Админ' }] : []),
  ];
  const showTabs = !top && !onboarding;

  return (
    <>
      {sky && <Sky />}
      <main ref={mainRef} className={showTabs ? 'app with-tabs' : 'app'}>
        {top ? <div key={stack.length} className="screen-enter">{renderRoute(top)}</div> : <div key={tab} className="screen-fade">{renderTab()}</div>}
      </main>

      {notice && <Notice kind={notice.kind} text={notice.text} onClose={() => setNotice(null)} />}

      {showTabs && (
        <nav className="tabbar" aria-label={t.tabMailboxes}>
          {tabs.map((item) => (
            <button
              key={item.id}
              type="button"
              className="tab"
              aria-current={tab === item.id ? 'page' : undefined}
              onClick={() => setTab(item.id)}
            >
              <Icon name={item.icon} size={24} />
              {item.label}
            </button>
          ))}
        </nav>
      )}
    </>
  );
}

export default App
