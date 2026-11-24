// Готовые настройки популярных почтовых сервисов и инструкции, как получить
// пароль приложения. Пользователь вводит только адрес и этот пароль.
// Тексты на двух языках: *En - для английского интерфейса.
import { lang } from './i18n'

/**
 * Шаг инструкции: просто текст или текст с кнопкой, которая сразу открывает
 * нужную страницу настроек почты. url может зависеть от адреса ящика
 * (Google открывает страницу сразу в аккаунте этой почты).
 */
export type ProviderStep = string | {
  text: string;
  url: string | ((email: string) => string);
  button: string;
};

export interface MailProvider {
  id: string;
  title: string;
  host: string;
  domains: string[];
  /** Шаги получения пароля приложения (по порядку). */
  steps: ProviderStep[];
  /** Подсказка под полем пароля: как выглядит правильный пароль. */
  passwordHint: string;
  titleEn?: string;
  stepsEn: ProviderStep[];
  passwordHintEn: string;
  /** Как включить пересылку на адрес бота (режим без пароля). Нет - общая инструкция. */
  forwardSteps?: ProviderStep[];
  forwardStepsEn?: ProviderStep[];
}

// Страница аккаунта Google. authuser выбирает нужный аккаунт, если в браузере их несколько
const googleAccount = (path: string) => (email: string) => {
  const address = email.trim();
  return `https://myaccount.google.com/${path}` + (address.includes('@') ? `?authuser=${encodeURIComponent(address)}` : '');
};

const MAILRU_APP_PASSWORDS_URL = 'https://account.mail.ru/user/2-step-auth/passwords?back_url=https%3A%2F%2Fid.mail.ru%2Fsecurity';
const YANDEX_IMAP_URL = 'https://mail.yandex.ru/#setup/client';
const YANDEX_APP_PASSWORDS_URL = 'https://id.yandex.ru/security/app-passwords';
const APPLE_ACCOUNT_URL = 'https://account.apple.com/account/manage';
const MAILRU_FORWARD_URL = 'https://e.mail.ru/settings/filters';
const YANDEX_FORWARD_URL = 'https://mail.yandex.ru/#setup/filters';
// Настройки пересылки Gmail; authuser выбирает нужный аккаунт, если их несколько
const gmailForwarding = (email: string) => {
  const address = email.trim();
  return 'https://mail.google.com/mail/' + (address.includes('@') ? `?authuser=${encodeURIComponent(address)}` : 'u/0/') + '#settings/fwdandpop';
};

export const CUSTOM_PROVIDER_ID = 'custom';

export const PROVIDERS: MailProvider[] = [
  {
    id: 'mailru',
    title: 'Mail.ru',
    host: 'imap.mail.ru',
    domains: ['mail.ru', 'list.ru', 'bk.ru', 'inbox.ru', 'internet.ru', 'mail.ua'],
    steps: [
      {
        text: 'Откройте «Пароли для внешних приложений» в настройках Mail.ru и нажмите «Создать».',
        url: MAILRU_APP_PASSWORDS_URL,
        button: 'Создать пароль приложения',
      },
      'Введите название, например «Почта в Telegram», выберите полный доступ к почте (IMAP и SMTP), чтобы можно было отвечать на письма из Telegram, и подтвердите вход.',
      'Скопируйте пароль: он показывается один раз.',
    ],
    passwordHint: 'Пароль для внешнего приложения из настроек Mail.ru, а не пароль от почты.',
    stepsEn: [
      {
        text: 'Open "Passwords for external applications" in Mail.ru settings and click "Create".',
        url: MAILRU_APP_PASSWORDS_URL,
        button: 'Create an app password',
      },
      'Enter a name, for example "Email in Telegram", choose full mail access (IMAP and SMTP) so you can reply from Telegram, and confirm.',
      'Copy the password: it is shown only once.',
    ],
    passwordHintEn: 'A password for an external application from Mail.ru settings, not your email password.',
    forwardSteps: [
      {
        text: 'В настройках Mail.ru откройте «Фильтры и пересылка» и нажмите «Добавить пересылку».',
        url: MAILRU_FORWARD_URL,
        button: 'Открыть настройки пересылки',
      },
      'Вставьте адрес выше. Mail.ru может попросить пароль от почты: он вводится на сайте Mail.ru, боту он не передается.',
      'Mail.ru пришлет на этот адрес письмо со ссылкой подтверждения. Оно придет сюда, в чат с ботом: откройте его кнопкой «Текст», перейдите по ссылке и включите пересылку.',
    ],
    forwardStepsEn: [
      {
        text: 'In Mail.ru settings open "Filters and forwarding" and click "Add forwarding".',
        url: MAILRU_FORWARD_URL,
        button: 'Open forwarding settings',
      },
      'Paste the address above. Mail.ru may ask for your email password: you enter it on the Mail.ru site, the bot never gets it.',
      'Mail.ru will send a confirmation link to this address. It arrives here, in the chat with the bot: open it with the "Text" button, follow the link and turn forwarding on.',
    ],
  },
  {
    id: 'yandex',
    title: 'Яндекс Почта',
    host: 'imap.yandex.ru',
    domains: ['yandex.ru', 'ya.ru', 'yandex.com', 'yandex.by', 'yandex.kz', 'narod.ru'],
    steps: [
      {
        text: 'Включите доступ по протоколу IMAP в настройках Яндекс Почты, раздел «Почтовые программы».',
        url: YANDEX_IMAP_URL,
        button: 'Открыть настройки IMAP',
      },
      {
        text: 'Создайте пароль приложения для «Почты» с названием, например, «Почта в Telegram».',
        url: YANDEX_APP_PASSWORDS_URL,
        button: 'Создать пароль приложения',
      },
      'Скопируйте пароль сразу: Яндекс показывает его только один раз.',
    ],
    passwordHint: 'Пароль приложения из Яндекс ID, а не пароль от почты.',
    titleEn: 'Yandex Mail',
    stepsEn: [
      {
        text: 'Enable IMAP access in Yandex Mail settings, "Email clients".',
        url: YANDEX_IMAP_URL,
        button: 'Open IMAP settings',
      },
      {
        text: 'Create an app password for "Mail" with a name, for example "Email in Telegram".',
        url: YANDEX_APP_PASSWORDS_URL,
        button: 'Create an app password',
      },
      'Copy the password right away: Yandex shows it only once.',
    ],
    passwordHintEn: 'An app password from Yandex ID, not your email password.',
    forwardSteps: [
      {
        text: 'В настройках Яндекс Почты откройте «Правила обработки писем» и нажмите «Создать правило».',
        url: YANDEX_FORWARD_URL,
        button: 'Открыть правила',
      },
      'Уберите условия, чтобы правило срабатывало для всех писем, отметьте «Переслать по адресу» и вставьте адрес выше. Если Яндекс попросит пароль, он вводится на сайте Яндекса, боту он не передается.',
      'Яндекс пришлет на этот адрес письмо со ссылкой подтверждения. Оно придет сюда, в чат с ботом: откройте его кнопкой «Текст» и перейдите по ссылке.',
    ],
    forwardStepsEn: [
      {
        text: 'In Yandex Mail settings open "Message filtering rules" and click "Create rule".',
        url: YANDEX_FORWARD_URL,
        button: 'Open rules',
      },
      'Remove the conditions so the rule applies to all emails, check "Forward to address" and paste the address above. If Yandex asks for a password, you enter it on the Yandex site, the bot never gets it.',
      'Yandex will send a confirmation link to this address. It arrives here, in the chat with the bot: open it with the "Text" button and follow the link.',
    ],
  },
  {
    id: 'gmail',
    title: 'Gmail',
    host: 'imap.gmail.com',
    domains: ['gmail.com', 'googlemail.com'],
    steps: [
      {
        text: 'Включите двухэтапную аутентификацию в аккаунте Google этой почты. Если она уже включена, пропустите шаг.',
        url: googleAccount('signinoptions/twosv'),
        button: 'Включить двухэтапную аутентификацию',
      },
      {
        text: 'Создайте пароль приложения: введите название, например «Почта в Telegram», и нажмите «Создать».',
        url: googleAccount('apppasswords'),
        button: 'Создать пароль приложения',
      },
      'Скопируйте 16 букв. Пробелы между ними можно не убирать.',
    ],
    passwordHint: '16 латинских букв, например abcd efgh ijkl mnop. Ваш обычный пароль Google не подойдет.',
    stepsEn: [
      {
        text: 'Turn on 2-Step Verification in the Google account of this mailbox. If it is already on, skip this step.',
        url: googleAccount('signinoptions/twosv'),
        button: 'Turn on 2-Step Verification',
      },
      {
        text: 'Create an app password: enter a name, for example "Email in Telegram", and click "Create".',
        url: googleAccount('apppasswords'),
        button: 'Create an app password',
      },
      'Copy the 16 letters. You can keep the spaces between them.',
    ],
    passwordHintEn: '16 Latin letters, for example abcd efgh ijkl mnop. Your regular Google password will not work.',
    forwardSteps: [
      {
        text: 'Откройте настройки Gmail, вкладку «Пересылка и POP/IMAP». На телефоне откройте полную версию Gmail в браузере: в приложении Gmail этой настройки нет.',
        url: gmailForwarding,
        button: 'Открыть настройки пересылки',
      },
      'Нажмите «Добавить адрес пересылки» и вставьте адрес выше. Если вместо кнопки там поле ввода, вставьте адрес в него и нажмите «Сохранить изменения»: Gmail покажет ошибку, это нормально.',
      'Google пришлет на адрес письмо с подтверждением. Оно придет сюда, в чат с ботом: откройте его кнопкой «Текст» и перейдите по ссылке.',
      'Обновите страницу настроек Gmail, выберите «Пересылать копии входящих сообщений на адрес…» и нажмите «Сохранить изменения» внизу страницы.',
    ],
    forwardStepsEn: [
      {
        text: 'Open Gmail settings, the "Forwarding and POP/IMAP" tab. On a phone, open the full Gmail site in a browser: the Gmail app does not have this setting.',
        url: gmailForwarding,
        button: 'Open forwarding settings',
      },
      'Click "Add a forwarding address" and paste the address above. If there is a text field instead of the button, paste the address there and click "Save Changes": Gmail shows an error, that is expected.',
      'Google will send a confirmation email to the address. It arrives here, in the chat with the bot: open it with the "Text" button and follow the link.',
      'Reload Gmail settings, choose "Forward a copy of incoming mail to…" and click "Save Changes" at the bottom of the page.',
    ],
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
      {
        text: 'Откройте аккаунт Apple, раздел «Вход и безопасность», затем «Пароли приложений».',
        url: APPLE_ACCOUNT_URL,
        button: 'Открыть аккаунт Apple',
      },
      'Создайте пароль с названием, например, «Почта в Telegram».',
      'Скопируйте код вида xxxx-xxxx-xxxx-xxxx.',
    ],
    passwordHint: 'Код вида xxxx-xxxx-xxxx-xxxx, а не пароль от Apple ID.',
    stepsEn: [
      {
        text: 'Open your Apple Account, "Sign-In and Security", then "App-Specific Passwords".',
        url: APPLE_ACCOUNT_URL,
        button: 'Open Apple Account',
      },
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

const FORWARD_STEPS_RU: ProviderStep[] = [
  'Найдите в настройках своей почты раздел «Пересылка» (бывает в «Правилах» или «Фильтрах»).',
  'Включите пересылку всех входящих писем на адрес выше.',
  'Если почта попросит подтвердить адрес, письмо с кодом или ссылкой придет сюда, в чат с ботом.',
];

const FORWARD_STEPS_EN: ProviderStep[] = [
  'Find the "Forwarding" section in your email settings (sometimes under "Rules" or "Filters").',
  'Turn on forwarding of all incoming emails to the address above.',
  'If your email asks to confirm the address, the email with a code or link arrives here, in the chat with the bot.',
];

/** Как включить пересылку: инструкция сервиса или общая. */
export const forwardSteps = (provider: MailProvider | undefined): ProviderStep[] =>
  (lang === 'ru' ? provider?.forwardSteps : provider?.forwardStepsEn) ?? (lang === 'ru' ? FORWARD_STEPS_RU : FORWARD_STEPS_EN);

export const providerTitle = (provider: MailProvider) =>
  lang === 'ru' ? provider.title : provider.titleEn ?? provider.title;

export const stepKey = (step: ProviderStep) => (typeof step === 'string' ? step : step.text);

export const stepUrl = (step: Exclude<ProviderStep, string>, email: string) =>
  typeof step.url === 'function' ? step.url(email) : step.url;

export const providerSteps = (provider: MailProvider): ProviderStep[] =>
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
