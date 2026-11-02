// Готовые настройки популярных почтовых сервисов и инструкции, как получить
// пароль приложения. Пользователь вводит только адрес и этот пароль.
// Тексты на двух языках: *En - для английского интерфейса.
import { lang } from './i18n'

export interface MailProvider {
  id: string;
  title: string;
  host: string;
  domains: string[];
  /** Шаги получения пароля приложения (по порядку). */
  steps: string[];
  /** Подсказка под полем пароля: как выглядит правильный пароль. */
  passwordHint: string;
  titleEn?: string;
  stepsEn: string[];
  passwordHintEn: string;
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
      'Введите название, например «Почта в Telegram», выберите полный доступ к почте (IMAP и SMTP), чтобы можно было отвечать на письма из Telegram, и подтвердите вход.',
      'Скопируйте пароль: он показывается один раз.',
    ],
    passwordHint: 'Пароль для внешнего приложения из настроек Mail.ru, а не пароль от почты.',
    stepsEn: [
      'Open Mail.ru mail, click your account and choose "Password and security".',
      'Open "Passwords for external applications" and click "Create".',
      'Enter a name, for example "Email in Telegram", choose full mail access (IMAP and SMTP) so you can reply from Telegram, and confirm.',
      'Copy the password: it is shown only once.',
    ],
    passwordHintEn: 'A password for an external application from Mail.ru settings, not your email password.',
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
    titleEn: 'Yandex Mail',
    stepsEn: [
      'In Yandex Mail settings open "Email clients" and enable IMAP access.',
      'Open passport.yandex.ru/security/app-passwords (Yandex ID, "App passwords").',
      'Create a password for "Mail" with a name, for example "Email in Telegram".',
      'Copy the password right away: Yandex shows it only once.',
    ],
    passwordHintEn: 'An app password from Yandex ID, not your email password.',
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
    stepsEn: [
      'Sign in to the Google account of this mailbox and turn on 2-Step Verification ("Security" section).',
      'Open myaccount.google.com/apppasswords.',
      'Enter a name, for example "Email in Telegram", and click "Create".',
      'Copy the 16 letters without spaces.',
    ],
    passwordHintEn: 'Exactly 16 Latin letters, for example abcdefghijklmnop. Your regular Google password will not work.',
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
    titleEn: 'Rambler',
    stepsEn: [
      'In Rambler mail settings open "Programs" and enable access for email clients.',
      'If two-factor protection is on, click "I use an email client" in your Rambler ID profile and copy the code.',
      'Without two-factor protection your regular email password works.',
    ],
    passwordHintEn: 'The "I use an email client" code, or your email password if two-factor protection is off.',
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
    stepsEn: [
      'Open appleid.apple.com, "Sign-In and Security", then "App-Specific Passwords".',
      'Create a password with a name, for example "Email in Telegram".',
      'Copy the code that looks like xxxx-xxxx-xxxx-xxxx.',
    ],
    passwordHintEn: 'A code like xxxx-xxxx-xxxx-xxxx, not your Apple ID password.',
  },
];

const CUSTOM_STEPS_RU = [
  'Узнайте в справке своего почтового сервиса адрес IMAP-сервера (подключение по SSL, порт 993).',
  'Проверьте, нужен ли сервису пароль приложения. Почти все крупные сервисы требуют именно его.',
];

const CUSTOM_STEPS_EN = [
  'Find the IMAP server address in your email provider\'s help (SSL connection, port 993).',
  'Check whether the provider requires an app password. Almost all major providers do.',
];

export const CUSTOM_STEPS = lang === 'ru' ? CUSTOM_STEPS_RU : CUSTOM_STEPS_EN;

export const providerTitle = (provider: MailProvider) =>
  lang === 'ru' ? provider.title : provider.titleEn ?? provider.title;

export const providerSteps = (provider: MailProvider) =>
  lang === 'ru' ? provider.steps : provider.stepsEn;

export const providerHint = (provider: MailProvider) =>
  lang === 'ru' ? provider.passwordHint : provider.passwordHintEn;

export function detectProvider(email: string): MailProvider | undefined {
  const domain = email.trim().toLowerCase().split('@')[1];
  if (!domain) return undefined;
  return PROVIDERS.find((provider) => provider.domains.includes(domain));
}

export function findProvider(id: string): MailProvider | undefined {
  return PROVIDERS.find((provider) => provider.id === id);
}
