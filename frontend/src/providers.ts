// Готовые настройки популярных почтовых сервисов. Пользователь вводит только
// адрес и пароль приложения, сервер подставляется автоматически.

export interface MailProvider {
  id: string;
  title: string;
  host: string;
  domains: string[];
  hint: string;
}

export const CUSTOM_PROVIDER_ID = 'custom';

export const PROVIDERS: MailProvider[] = [
  {
    id: 'mailru',
    title: 'Mail.ru (mail, list, bk, inbox)',
    host: 'imap.mail.ru',
    domains: ['mail.ru', 'list.ru', 'bk.ru', 'inbox.ru', 'internet.ru', 'mail.ua'],
    hint: 'Создайте пароль для внешнего приложения в настройках безопасности Mail.ru.',
  },
  {
    id: 'yandex',
    title: 'Яндекс Почта',
    host: 'imap.yandex.ru',
    domains: ['yandex.ru', 'ya.ru', 'yandex.com', 'yandex.by', 'yandex.kz', 'narod.ru'],
    hint: 'Включите доступ по IMAP в настройках почты и создайте пароль приложения в Яндекс ID.',
  },
  {
    id: 'gmail',
    title: 'Gmail',
    host: 'imap.gmail.com',
    domains: ['gmail.com', 'googlemail.com'],
    hint: 'Нужна двухэтапная аутентификация и пароль приложения из настроек аккаунта Google.',
  },
  {
    id: 'rambler',
    title: 'Рамблер',
    host: 'imap.rambler.ru',
    domains: ['rambler.ru', 'lenta.ru', 'ro.ru', 'autorambler.ru', 'myrambler.ru'],
    hint: 'Разрешите доступ по IMAP в настройках почты Рамблера.',
  },
  {
    id: 'icloud',
    title: 'iCloud',
    host: 'imap.mail.me.com',
    domains: ['icloud.com', 'me.com', 'mac.com'],
    hint: 'Создайте пароль для приложения в настройках Apple ID.',
  },
];

export function detectProvider(email: string): MailProvider | undefined {
  const domain = email.trim().toLowerCase().split('@')[1];
  if (!domain) return undefined;
  return PROVIDERS.find((provider) => provider.domains.includes(domain));
}

export function findProvider(id: string): MailProvider | undefined {
  return PROVIDERS.find((provider) => provider.id === id);
}
