// Готовые настройки популярных почтовых сервисов и инструкции, как получить
// пароль приложения. Пользователь вводит только адрес и этот пароль.

export interface MailProvider {
  id: string;
  title: string;
  host: string;
  domains: string[];
  /** Шаги получения пароля приложения (по порядку). */
  steps: string[];
  /** Подсказка под полем пароля: как выглядит правильный пароль. */
  passwordHint: string;
}

export const CUSTOM_PROVIDER_ID = 'custom';

export const PROVIDERS: MailProvider[] = [
  {
    id: 'mailru',
    title: 'Mail.ru',
    host: 'imap.mail.ru',
    domains: ['mail.ru', 'list.ru', 'bk.ru', 'inbox.ru', 'internet.ru', 'mail.ua'],
    steps: [
      'Откройте почту Mail.ru, нажмите на свой аккаунт и выберите «Пароль и безопасность».',
      'Откройте «Пароли для внешних приложений» и нажмите «Создать».',
      'Введите название, например «Почта в Telegram», выберите доступ к почте и подтвердите вход.',
      'Скопируйте пароль: он показывается один раз.',
    ],
    passwordHint: 'Пароль для внешнего приложения из настроек Mail.ru, а не пароль от почты.',
  },
  {
    id: 'yandex',
    title: 'Яндекс Почта',
    host: 'imap.yandex.ru',
    domains: ['yandex.ru', 'ya.ru', 'yandex.com', 'yandex.by', 'yandex.kz', 'narod.ru'],
    steps: [
      'В настройках Яндекс Почты откройте «Почтовые программы» и включите доступ по протоколу IMAP.',
      'Откройте passport.yandex.ru/security/app-passwords (Яндекс ID, раздел «Пароли приложений»).',
      'Создайте пароль для «Почты» с названием, например, «Почта в Telegram».',
      'Скопируйте пароль сразу: Яндекс показывает его только один раз.',
    ],
    passwordHint: 'Пароль приложения из Яндекс ID, а не пароль от почты.',
  },
  {
    id: 'gmail',
    title: 'Gmail',
    host: 'imap.gmail.com',
    domains: ['gmail.com', 'googlemail.com'],
    steps: [
      'Войдите в аккаунт Google этой почты и включите двухэтапную аутентификацию (раздел «Безопасность»).',
      'Откройте myaccount.google.com/apppasswords.',
      'Введите название, например «Почта в Telegram», и нажмите «Создать».',
      'Скопируйте 16 букв без пробелов.',
    ],
    passwordHint: 'Ровно 16 латинских букв, например abcdefghijklmnop. Ваш обычный пароль Google не подойдет.',
  },
  {
    id: 'rambler',
    title: 'Рамблер',
    host: 'imap.rambler.ru',
    domains: ['rambler.ru', 'lenta.ru', 'ro.ru', 'autorambler.ru', 'myrambler.ru'],
    steps: [
      'В настройках почты Рамблера откройте раздел «Программы» и включите доступ для почтовых клиентов.',
      'Если в аккаунте включена двухфакторная защита, в профиле Рамблер ID нажмите «Я использую почтовый клиент» и скопируйте код.',
      'Без двухфакторной защиты подойдет обычный пароль от почты.',
    ],
    passwordHint: 'Код «Я использую почтовый клиент» или пароль от почты, если двухфакторная защита выключена.',
  },
  {
    id: 'icloud',
    title: 'iCloud',
    host: 'imap.mail.me.com',
    domains: ['icloud.com', 'me.com', 'mac.com'],
    steps: [
      'Откройте appleid.apple.com, раздел «Вход и безопасность», затем «Пароли приложений».',
      'Создайте пароль с названием, например, «Почта в Telegram».',
      'Скопируйте код вида xxxx-xxxx-xxxx-xxxx.',
    ],
    passwordHint: 'Код вида xxxx-xxxx-xxxx-xxxx, а не пароль от Apple ID.',
  },
];

export const CUSTOM_STEPS = [
  'Узнайте в справке своего почтового сервиса адрес IMAP-сервера (подключение по SSL, порт 993).',
  'Проверьте, нужен ли сервису пароль приложения. Почти все крупные сервисы требуют именно его.',
];

export function detectProvider(email: string): MailProvider | undefined {
  const domain = email.trim().toLowerCase().split('@')[1];
  if (!domain) return undefined;
  return PROVIDERS.find((provider) => provider.domains.includes(domain));
}

export function findProvider(id: string): MailProvider | undefined {
  return PROVIDERS.find((provider) => provider.id === id);
}
