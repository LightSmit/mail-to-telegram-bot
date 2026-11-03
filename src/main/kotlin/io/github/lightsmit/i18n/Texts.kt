package io.github.lightsmit.i18n

/** Язык интерфейса. Русский - для русского, украинского, белорусского и казахского Telegram, остальным английский. */
enum class Lang {
    RU,
    EN;

    val texts: Texts get() = if (this == RU) RuTexts else EnTexts

    companion object {
        private val russianSpeaking = setOf("ru", "uk", "be", "kk")

        /** language_code из Telegram ("ru", "en-US"...). Без кода считаем русским: основная аудитория. */
        fun fromCode(code: String?): Lang {
            val base = code?.trim()?.lowercase()?.substringBefore('-')?.takeIf(String::isNotEmpty) ?: return RU
            return if (base in russianSpeaking) RU else EN
        }
    }
}

/** Все тексты, которые видит пользователь (кроме команд администратора). */
interface Texts {
    // Общие
    val openApp: String

    // Тарифы, оплата, коды подтверждения
    val billing: BillingTexts

    // Описание бота в Telegram: в пустом чате и в профиле
    val botDescription: String
    val botShortDescription: String

    // Команды
    fun start(): String
    fun help(replyEnabled: Boolean): String
    val defaultReply: String
    val commandStart: String
    val commandMailboxes: String
    val commandHelp: String
    val commandLanguage: String

    // Выбор языка
    val languageSet: String

    // /mailboxes
    val mailboxesTitle: String
    val mailboxesEmpty: String
    val mailboxesFooter: String
    val statusOk: String
    val statusConnecting: String
    val statusPaused: String
    val statusErrorFallback: String
    val mailboxesAppOnly: String

    // Проблемы ящика (карточка в приложении, /mailboxes, уведомления)
    val problemAuth: String
    fun problemConnect(host: String): String

    // Кнопки под письмом и ответы на нажатия
    val buttonText: String
    val buttonAttachments: String
    fun buttonAttachmentsCount(count: Int): String
    val buttonReply: String
    val buttonRead: String
    val buttonBack: String
    val accessDenied: String
    val badButton: String
    val mailboxGone: String
    val cannotFindMessage: String
    val alreadyProcessing: String
    val openingText: String
    val sendingAttachmentsShort: String
    val markedRead: String
    val messageGone: String
    val actionFailed: String

    // Карточка письма
    val newEmail: String
    val fullEmail: String
    val labelMailbox: String
    val labelTo: String
    val labelFrom: String
    val labelSubject: String
    val labelDate: String
    val labelAttachments: String
    val attachmentsChecking: String
    val attachmentsNone: String
    fun attachmentCount(count: Int): String
    val dateUnknown: String
    val noBody: String
    val noExtension: String
    val noSubject: String
    val unknownSender: String

    // Вложения
    val noAttachments: String
    fun sendingAttachments(done: Int, total: Int): String
    fun attachmentsSent(total: Int): String
    val attachmentsFailed: String
    val skippedTitle: String
    fun skipReason(code: String): String
    val cannotOpenEmail: String

    // Служебные сообщения о ящике
    fun mailboxConnected(name: String, address: String): String
    fun uidValidityChanged(name: String, address: String): String
    fun mailboxRecovered(name: String, address: String): String
    fun mailboxFailing(name: String, address: String, reason: String): String

    // Ответ на письмо
    val replyDisabled: String
    fun replyPrompt(subject: String, recipients: String, mailbox: String): String
    val replyPlaceholder: String
    val replyNoRecipient: String
    val replyUnsupported: String
    fun replySent(recipients: String): String
    val replyExpired: String
    fun replyTooLong(max: Int): String
    val replyMailboxGone: String
    val replyAuthFailed: String
    val replyFailed: String
    val replyTextOnly: String
    val replyCancelled: String
    val nothingToCancel: String

    // Удаление данных
    fun deleteWarning(mailboxes: Int): String
    fun deleteDone(mailboxes: Int, backupKeepDays: Int): String
    val deleteFailed: String

    // Ошибки приложения (API)
    val enterAddress: String
    val enterPassword: String
    val onlyImaps: String
    val badHost: String
    fun hostNotFound(host: String): String
    fun hostNotAllowed(host: String): String
    fun serverTimeout(host: String): String
    val loginRejected: String
    fun cannotConnect(host: String): String
    fun limitReached(limit: Int): String
    val mailboxNotFound: String
    val invalidMailbox: String
    val badRequest: String
    val unavailable: String
    val timeFormat: String
    val emailNotFound: String
    val browseAuth: String
    val browseNoSent: String
    val browseFailed: String
    val browseTimeout: String
}

object RuTexts : Texts {
    override val openApp = "📬 Открыть приложение"
    override val billing: BillingTexts = RuBillingTexts
    override val botDescription = "📬 Присылаю новые письма из Mail.ru, Gmail, Яндекса, iCloud и любой почты с IMAP прямо в Telegram за несколько секунд.\n\n" +
        "🔐 Нужен не пароль от почты, а отдельный пароль приложения: его можно отозвать в любой момент. " +
        "Пароли хранятся в зашифрованном виде, сами письма бот не сохраняет. Удалить все данные - /delete_my_data.\n\n" +
        "Нажмите «Начать» и подключите ящик за 2 минуты."
    override val botShortDescription = "Новые письма из Mail.ru, Gmail, Яндекса и любой почты прямо в Telegram за секунды."

    override fun start() = """
        👋 Привет! Я пересылаю новые письма из ваших почтовых ящиков прямо сюда, в Telegram.

        Нажмите «Открыть приложение», чтобы подключить первый ящик. Понадобится адрес почты и пароль приложения, инструкция для вашего почтового сервиса будет там же.

        /help - подробно о том, как все работает
        /mailboxes - мои ящики и их состояние
        /language - язык / language
    """.trimIndent()

    override fun help(replyEnabled: Boolean) = buildString {
        appendLine("📖 Как это работает")
        appendLine()
        appendLine("Я подключаюсь к вашему ящику по протоколу IMAP и присылаю сюда карточку каждого нового письма: от кого, тема, дата и есть ли вложения. Старые письма не пересылаются.")
        appendLine()
        appendLine("Как подключить ящик")
        appendLine("1. Откройте приложение кнопкой ниже.")
        appendLine("2. Введите адрес почты, сервис определится сам.")
        appendLine("3. Создайте в настройках почты пароль приложения по инструкции в приложении и вставьте его. Обычный пароль от почты не подойдет: почтовые сервисы не пускают по нему сторонние программы.")
        appendLine("4. Нажмите «Подключить ящик». Я проверю вход и напишу, что ящик подключен.")
        appendLine()
        appendLine("Кнопки под письмом")
        appendLine("📄 Текст - показать текст письма")
        appendLine("📎 Вложения - прислать файлы из письма")
        if (replyEnabled) appendLine("↩️ Ответить - ответить на письмо прямо отсюда: напишите текст, и я отправлю его с вашего ящика")
        appendLine("✅ Прочитано - отметить письмо прочитанным в почте")
        appendLine()
        appendLine("В приложении можно открыть любой ящик и посмотреть его письма: входящие и отправленные, с поиском и фильтрами.")
        appendLine()
        appendLine("Тихие часы (письма без звука) и сводку (письма пачкой раз в несколько часов) можно включить в приложении, раздел «Уведомления».")
        appendLine()
        appendLine("Если ящик перестанет работать (например, вы сменили пароль от почты), я напишу об этом. Новый пароль приложения можно ввести в приложении, в настройках ящика.")
        appendLine()
        appendLine("Поддерживаются Mail.ru, Яндекс, Gmail, Рамблер, iCloud и другие ящики с доступом по IMAP.")
        appendLine()
        append("/delete_my_data - удалить все свои данные из бота")
    }

    override val defaultReply = "Я не отвечаю на сообщения, зато пересылаю сюда новые письма 🙂\n\n" +
        "Команды: /mailboxes - мои ящики, /help - как подключить почту."
    override val commandStart = "Начать и открыть приложение"
    override val commandMailboxes = "Мои ящики и их состояние"
    override val commandHelp = "Как подключить почту"
    override val commandLanguage = "Язык / Language"

    override val languageSet = "✅ Язык бота: русский. Сменить - /language"

    override val mailboxesTitle = "📬 Ваши ящики"
    override val mailboxesEmpty = "У вас пока нет подключенных ящиков. Подключите первый в приложении, инструкция там же."
    override val mailboxesFooter = "Подключить или отключить ящик можно в приложении."
    override val statusOk = "письма приходят"
    override val statusConnecting = "подключается"
    override val statusPaused = "на паузе"
    override val statusErrorFallback = "не удается подключиться"
    override val mailboxesAppOnly = "Список ящиков открывается в приложении."

    override val problemAuth = "Почта отклонила пароль приложения (его могли удалить, или сменился пароль от почты)"
    override fun problemConnect(host: String) = "Не удается подключиться к серверу $host"

    override val buttonText = "📄 Текст"
    override val buttonAttachments = "📎 Вложения"
    override fun buttonAttachmentsCount(count: Int) = "📎 Вложения · $count"
    override val buttonReply = "↩️ Ответить"
    override val buttonRead = "✅ Прочитано"
    override val buttonBack = "← Назад"
    override val accessDenied = "Доступ запрещен"
    override val badButton = "Некорректная кнопка"
    override val mailboxGone = "Почтовый ящик больше не подключен"
    override val cannotFindMessage = "Не удалось определить сообщение"
    override val alreadyProcessing = "Письмо уже обрабатывается"
    override val openingText = "Открываю текст…"
    override val sendingAttachmentsShort = "Отправляю вложения…"
    override val markedRead = "✅ Отмечено прочитанным в ящике"
    override val messageGone = "Письмо не найдено: его могли удалить или переместить"
    override val actionFailed = "⚠️ Не удалось выполнить действие. Повторите попытку."

    override val newEmail = "📨 Новое письмо"
    override val fullEmail = "📄 Письмо"
    override val labelMailbox = "📬 Ящик"
    override val labelTo = "📧 Кому"
    override val labelFrom = "👤 От"
    override val labelSubject = "📝 Тема"
    override val labelDate = "🕒 Дата"
    override val labelAttachments = "📎 Вложения"
    override val attachmentsChecking = "проверяются…"
    override val attachmentsNone = "нет"

    override fun attachmentCount(count: Int): String {
        if (count == 0) return attachmentsNone
        val lastTwo = count % 100
        val last = count % 10
        val noun = when {
            lastTwo in 11..14 -> "файлов"
            last == 1 -> "файл"
            last in 2..4 -> "файла"
            else -> "файлов"
        }
        return "$count $noun"
    }

    override val dateUnknown = "неизвестна"
    override val noBody = "(текст письма отсутствует или не удалось распознать)"
    override val noExtension = "без расширения"
    override val noSubject = "(без темы)"
    override val unknownSender = "(отправитель неизвестен)"

    override val noAttachments = "У этого письма нет вложений."
    override fun sendingAttachments(done: Int, total: Int) = "⏳ Отправляю вложения: $done/$total"
    override fun attachmentsSent(total: Int) = "✅ Вложения отправлены: $total"
    override val attachmentsFailed = "⚠️ Не удалось отправить вложения. Повторите попытку."
    override val skippedTitle = "⚠️ Некоторые вложения не были отправлены"
    override fun skipReason(code: String) = when (code) {
        SKIP_TOO_LARGE -> "размер превышает установленный предел"
        else -> code
    }
    override val cannotOpenEmail = "⚠️ Не удалось открыть письмо\n\nПисьмо могло быть удалено или перемещено из папки «Входящие»."

    override fun mailboxConnected(name: String, address: String) =
        "✅ Почтовый ящик подключен\n\nНазвание: $name\nАдрес: $address\nСтарые письма пересылаться не будут."
    override fun uidValidityChanged(name: String, address: String) =
        "⚠️ Нумерация писем была обновлена\n\nЯщик: $name\nАдрес: $address"
    override fun mailboxRecovered(name: String, address: String) =
        "✅ Ящик «$name» ($address) снова работает. Новые письма будут приходить, как обычно."
    override fun mailboxFailing(name: String, address: String, reason: String) = buildString {
        appendLine("⚠️ Не удается проверить ящик «$name» ($address)")
        appendLine()
        appendLine("Причина: ${reason.replaceFirstChar(Char::lowercase)}.")
        appendLine()
        appendLine("Если вы меняли пароль от почты или удаляли пароль приложения, создайте новый пароль приложения и введите его в приложении: «Настройки» у этого ящика, раздел «Пароль приложения». Фильтры при этом сохранятся.")
        append("Бот продолжит попытки и напишет, когда ящик снова заработает.")
    }

    override val replyDisabled = "Ответ на письма из Telegram временно отключен"
    override fun replyPrompt(subject: String, recipients: String, mailbox: String) =
        "↩️ Ответ на «$subject»\nКому: $recipients\nС ящика: $mailbox\n\nНапишите текст ответа следующим сообщением. /cancel - отменить."
    override val replyPlaceholder = "Текст ответа"
    override val replyNoRecipient = "В письме нет адреса для ответа"
    override val replyUnsupported = "Для этого почтового сервиса ответ из Telegram пока не поддерживается"
    override fun replySent(recipients: String) = "✅ Ответ отправлен: $recipients"
    override val replyExpired = "Время на ответ вышло. Нажмите «↩️ Ответить» под письмом еще раз."
    override fun replyTooLong(max: Int) = "Слишком длинный ответ: не больше $max символов. Сократите текст и отправьте снова."
    override val replyMailboxGone = "Этот ящик больше не подключен, ответ не отправлен."
    override val replyAuthFailed = "⚠️ Почта не приняла пароль приложения для отправки писем. Проверьте, что пароль приложения разрешает отправку (SMTP), и обновите его в настройках ящика."
    override val replyFailed = "⚠️ Не удалось связаться с почтовым сервером для отправки. Попробуйте отправить текст еще раз позже или /cancel."
    override val replyTextOnly = "Пока можно ответить только текстом. Напишите ответ или отправьте /cancel."
    override val replyCancelled = "Ответ отменен."
    override val nothingToCancel = "Сейчас нечего отменять."

    override fun deleteWarning(mailboxes: Int) =
        "⚠️ Будут удалены все ваши данные в боте: подключенные ящики ($mailboxes) вместе с паролями приложений, " +
            "фильтры, настройки уведомлений и очередь писем. Письма в самих ящиках не пострадают.\n\n" +
            "Чтобы подтвердить, отправьте /delete_my_data_confirm"
    override fun deleteDone(mailboxes: Int, backupKeepDays: Int) =
        "🗑 Готово: удалены ящики ($mailboxes), настройки и вся история. Бот больше не хранит ваших данных.\n\n" +
            "В резервных копиях базы они останутся еще до $backupKeepDays дн., потом удалятся автоматически. " +
            "Пароли приложений стоит удалить и в настройках самой почты.\n\n" +
            "Если у вас действующая платная подписка, она сохранится. Записи об оплатах звездами хранятся для возвратов."
    override val deleteFailed = "Не удалось удалить данные. Попробуйте еще раз позже."

    override val enterAddress = "Укажите адрес почты"
    override val enterPassword = "Укажите пароль приложения"
    override val onlyImaps = "Поддерживается только IMAP с шифрованием (порт 993)"
    override val badHost = "Некорректный адрес IMAP-сервера"
    override fun hostNotFound(host: String) = "Сервер $host не найден. Проверьте адрес (например, у Gmail это imap.gmail.com)"
    override fun hostNotAllowed(host: String) = "Адрес сервера $host не разрешен"
    override fun serverTimeout(host: String) = "Сервер $host не ответил вовремя. Попробуйте позже"
    override val loginRejected = "Почта отклонила вход. Проверьте адрес и пароль приложения, а также что доступ по IMAP включен в настройках ящика"
    override fun cannotConnect(host: String) = "Не удалось подключиться к $host. Проверьте адрес сервера"
    override fun limitReached(limit: Int) = "Можно подключить не больше $limit ящиков"
    override val mailboxNotFound = "Ящик не найден"
    override val invalidMailbox = "Некорректный ящик"
    override val badRequest = "Некорректный запрос"
    override val unavailable = "Недоступно"
    override val timeFormat = "Время укажите в виде ЧЧ:ММ"
    override val emailNotFound = "Письмо не найдено: его могли удалить или переместить"
    override val browseAuth = "Почта не пускает бота: обновите пароль приложения в настройках ящика"
    override val browseNoSent = "В этом ящике не нашлась папка «Отправленные»"
    override val browseFailed = "Не удалось получить письма из ящика. Попробуйте еще раз"
    override val browseTimeout = "Почтовый сервер отвечает слишком долго. Попробуйте еще раз"
}

object EnTexts : Texts {
    override val openApp = "📬 Open app"
    override val billing: BillingTexts = EnBillingTexts
    override val botDescription = "📬 I forward new emails from Gmail, Yandex, Mail.ru, iCloud and any IMAP mailbox right to Telegram within seconds.\n\n" +
        "🔐 You give me a separate app password, not your email password, and can revoke it at any time. " +
        "Passwords are stored encrypted, emails are not stored. Delete all your data with /delete_my_data.\n\n" +
        "Tap Start and connect a mailbox in 2 minutes."
    override val botShortDescription = "New emails from Gmail, Yandex, Mail.ru and any mailbox right in Telegram within seconds."

    override fun start() = """
        👋 Hi! I forward new emails from your mailboxes right here, to Telegram.

        Tap "Open app" to connect your first mailbox. You will need your email address and an app password; instructions for your email provider are in the app.

        /help - how everything works
        /mailboxes - my mailboxes and their status
        /language - language / язык
    """.trimIndent()

    override fun help(replyEnabled: Boolean) = buildString {
        appendLine("📖 How it works")
        appendLine()
        appendLine("I connect to your mailbox over IMAP and send a card here for every new email: sender, subject, date and attachments. Old emails are not forwarded.")
        appendLine()
        appendLine("How to connect a mailbox")
        appendLine("1. Open the app with the button below.")
        appendLine("2. Enter your email address, the provider is detected automatically.")
        appendLine("3. Create an app password in your email settings following the instructions in the app, and paste it. Your regular email password will not work: providers do not let third-party apps sign in with it.")
        appendLine("4. Tap \"Connect mailbox\". I will check the sign-in and let you know when the mailbox is connected.")
        appendLine()
        appendLine("Buttons under an email")
        appendLine("📄 Text - show the email text")
        appendLine("📎 Attachments - send the files from the email")
        if (replyEnabled) appendLine("↩️ Reply - reply right from here: write the text and I will send it from your mailbox")
        appendLine("✅ Read - mark the email as read in your mailbox")
        appendLine()
        appendLine("In the app you can open any mailbox and browse its emails: inbox and sent, with search and filters.")
        appendLine()
        appendLine("Quiet hours (emails without sound) and digest (emails delivered together every few hours) can be turned on in the app, in \"Notifications\".")
        appendLine()
        appendLine("If a mailbox stops working (for example, you changed your email password), I will let you know. You can enter a new app password in the app, in the mailbox settings.")
        appendLine()
        appendLine("Works with Gmail, Yandex, Mail.ru, iCloud, Rambler and other mailboxes with IMAP access.")
        appendLine()
        append("/delete_my_data - delete all your data from the bot")
    }

    override val defaultReply = "I don't reply to messages, but I forward your new emails here 🙂\n\n" +
        "Commands: /mailboxes - my mailboxes, /help - how to connect email."
    override val commandStart = "Start and open the app"
    override val commandMailboxes = "My mailboxes and their status"
    override val commandHelp = "How to connect email"
    override val commandLanguage = "Language / Язык"

    override val languageSet = "✅ Bot language: English. Change - /language"

    override val mailboxesTitle = "📬 Your mailboxes"
    override val mailboxesEmpty = "You have no connected mailboxes yet. Connect the first one in the app, instructions are there."
    override val mailboxesFooter = "You can connect or disconnect mailboxes in the app."
    override val statusOk = "emails are arriving"
    override val statusConnecting = "connecting"
    override val statusPaused = "paused"
    override val statusErrorFallback = "cannot connect"
    override val mailboxesAppOnly = "The list of mailboxes is in the app."

    override val problemAuth = "The mailbox rejected the app password (it may have been revoked, or the email password changed)"
    override fun problemConnect(host: String) = "Cannot connect to server $host"

    override val buttonText = "📄 Text"
    override val buttonAttachments = "📎 Attachments"
    override fun buttonAttachmentsCount(count: Int) = "📎 Attachments · $count"
    override val buttonReply = "↩️ Reply"
    override val buttonRead = "✅ Read"
    override val buttonBack = "← Back"
    override val accessDenied = "Access denied"
    override val badButton = "Invalid button"
    override val mailboxGone = "This mailbox is no longer connected"
    override val cannotFindMessage = "Could not find the message"
    override val alreadyProcessing = "This email is already being processed"
    override val openingText = "Opening the text…"
    override val sendingAttachmentsShort = "Sending attachments…"
    override val markedRead = "✅ Marked as read in the mailbox"
    override val messageGone = "Email not found: it may have been deleted or moved"
    override val actionFailed = "⚠️ Could not complete the action. Please try again."

    override val newEmail = "📨 New email"
    override val fullEmail = "📄 Email"
    override val labelMailbox = "📬 Mailbox"
    override val labelTo = "📧 To"
    override val labelFrom = "👤 From"
    override val labelSubject = "📝 Subject"
    override val labelDate = "🕒 Date"
    override val labelAttachments = "📎 Attachments"
    override val attachmentsChecking = "checking…"
    override val attachmentsNone = "none"
    override fun attachmentCount(count: Int) = when (count) {
        0 -> attachmentsNone
        1 -> "1 file"
        else -> "$count files"
    }
    override val dateUnknown = "unknown"
    override val noBody = "(the email has no text or it could not be read)"
    override val noExtension = "no extension"
    override val noSubject = "(no subject)"
    override val unknownSender = "(unknown sender)"

    override val noAttachments = "This email has no attachments."
    override fun sendingAttachments(done: Int, total: Int) = "⏳ Sending attachments: $done/$total"
    override fun attachmentsSent(total: Int) = "✅ Attachments sent: $total"
    override val attachmentsFailed = "⚠️ Could not send the attachments. Please try again."
    override val skippedTitle = "⚠️ Some attachments were not sent"
    override fun skipReason(code: String) = when (code) {
        SKIP_TOO_LARGE -> "the file is larger than the allowed limit"
        else -> code
    }
    override val cannotOpenEmail = "⚠️ Could not open the email\n\nIt may have been deleted or moved out of the Inbox."

    override fun mailboxConnected(name: String, address: String) =
        "✅ Mailbox connected\n\nName: $name\nAddress: $address\nOld emails will not be forwarded."
    override fun uidValidityChanged(name: String, address: String) =
        "⚠️ The mailbox renumbered its emails\n\nMailbox: $name\nAddress: $address"
    override fun mailboxRecovered(name: String, address: String) =
        "✅ Mailbox \"$name\" ($address) is working again. New emails will arrive as usual."
    override fun mailboxFailing(name: String, address: String, reason: String) = buildString {
        appendLine("⚠️ Cannot check mailbox \"$name\" ($address)")
        appendLine()
        appendLine("Reason: ${reason.replaceFirstChar(Char::lowercase)}.")
        appendLine()
        appendLine("If you changed your email password or revoked the app password, create a new app password and enter it in the app: \"Settings\" of this mailbox, \"App password\" section. Your filters will be kept.")
        append("The bot will keep trying and will let you know when the mailbox works again.")
    }

    override val replyDisabled = "Replying from Telegram is temporarily disabled"
    override fun replyPrompt(subject: String, recipients: String, mailbox: String) =
        "↩️ Reply to \"$subject\"\nTo: $recipients\nFrom mailbox: $mailbox\n\nWrite the reply in your next message. /cancel - cancel."
    override val replyPlaceholder = "Reply text"
    override val replyNoRecipient = "The email has no reply address"
    override val replyUnsupported = "Replying from Telegram is not supported for this email provider yet"
    override fun replySent(recipients: String) = "✅ Reply sent: $recipients"
    override val replyExpired = "The reply timed out. Tap \"↩️ Reply\" under the email again."
    override fun replyTooLong(max: Int) = "The reply is too long: $max characters at most. Shorten it and send again."
    override val replyMailboxGone = "This mailbox is no longer connected, the reply was not sent."
    override val replyAuthFailed = "⚠️ The mailbox did not accept the app password for sending. Make sure the app password allows sending (SMTP) and update it in the mailbox settings."
    override val replyFailed = "⚠️ Could not reach the mail server to send the reply. Try sending the text again later, or /cancel."
    override val replyTextOnly = "Only text replies are supported for now. Write your reply or send /cancel."
    override val replyCancelled = "Reply cancelled."
    override val nothingToCancel = "There is nothing to cancel."

    override fun deleteWarning(mailboxes: Int) =
        "⚠️ This will delete all your data in the bot: connected mailboxes ($mailboxes) with their app passwords, " +
            "filters, notification settings and the email queue. The emails in your mailboxes will not be affected.\n\n" +
            "To confirm, send /delete_my_data_confirm"
    override fun deleteDone(mailboxes: Int, backupKeepDays: Int) =
        "🗑 Done: mailboxes ($mailboxes), settings and all history are deleted. The bot no longer stores your data.\n\n" +
            "Database backups will keep it for up to $backupKeepDays days, then it is removed automatically. " +
            "You may also want to revoke the app passwords in your email settings.\n\n" +
            "An active paid subscription is kept. Records of Stars payments are kept for refunds."
    override val deleteFailed = "Could not delete the data. Please try again later."

    override val enterAddress = "Enter your email address"
    override val enterPassword = "Enter the app password"
    override val onlyImaps = "Only encrypted IMAP (port 993) is supported"
    override val badHost = "Invalid IMAP server address"
    override fun hostNotFound(host: String) = "Server $host not found. Check the address (for Gmail it is imap.gmail.com)"
    override fun hostNotAllowed(host: String) = "Server address $host is not allowed"
    override fun serverTimeout(host: String) = "Server $host did not respond in time. Try again later"
    override val loginRejected = "The mailbox rejected the sign-in. Check the address and the app password, and that IMAP access is enabled in the mailbox settings"
    override fun cannotConnect(host: String) = "Could not connect to $host. Check the server address"
    override fun limitReached(limit: Int) = "You can connect up to $limit mailboxes"
    override val mailboxNotFound = "Mailbox not found"
    override val invalidMailbox = "Invalid mailbox"
    override val badRequest = "Invalid request"
    override val unavailable = "Not available"
    override val timeFormat = "Enter the time as HH:MM"
    override val emailNotFound = "Email not found: it may have been deleted or moved"
    override val browseAuth = "The mailbox does not let the bot in: update the app password in the mailbox settings"
    override val browseNoSent = "The Sent folder was not found in this mailbox"
    override val browseFailed = "Could not load emails from the mailbox. Please try again"
    override val browseTimeout = "The mail server is taking too long to respond. Please try again"
}

/** Причина, по которой вложение не отправлено: файл больше лимита. */
const val SKIP_TOO_LARGE = "too_large"

/** Команды бота для подсказок при вводе "/": /premium и /paysupport - только при включенной оплате. */
fun botCommands(t: Texts, billingEnabled: Boolean): List<Pair<String, String>> = buildList {
    add("start" to t.commandStart)
    add("mailboxes" to t.commandMailboxes)
    if (billingEnabled) add("premium" to t.billing.commandPremium)
    if (billingEnabled) add("referral" to t.billing.commandReferral)
    add("help" to t.commandHelp)
    add("language" to t.commandLanguage)
    if (billingEnabled) add("paysupport" to t.billing.commandPaySupport)
}

/** Вопрос "какой язык" одинаков для всех: человек мог не понять текущий язык бота. */
const val LANGUAGE_PROMPT = "🌐 Выберите язык бота / Choose the bot language"
