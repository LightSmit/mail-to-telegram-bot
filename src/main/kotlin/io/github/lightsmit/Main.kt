package io.github.lightsmit

import io.github.lightsmit.api.ApiServer
import io.github.lightsmit.billing.BillingConfig
import io.github.lightsmit.billing.InvoiceLinks
import io.github.lightsmit.billing.SubscriptionNotice
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.config.Environment
import io.github.lightsmit.i18n.Lang
import io.github.lightsmit.i18n.botCommands
import io.github.lightsmit.mail.ImapIdleWatcher
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.mail.SmtpMailSender
import io.github.lightsmit.service.AdminService
import io.github.lightsmit.service.BackupService
import io.github.lightsmit.service.EmailContentLoader
import io.github.lightsmit.service.MailAccountRegistry
import io.github.lightsmit.service.MailAccountService
import io.github.lightsmit.service.MailboxHealthMonitor
import io.github.lightsmit.service.MailboxQuota
import io.github.lightsmit.service.MailBrowserService
import io.github.lightsmit.service.MailReplyService
import io.github.lightsmit.service.NotificationSettingsService
import io.github.lightsmit.service.MailboxSupervisor
import io.github.lightsmit.service.MailForwardingService
import io.github.lightsmit.service.MailNotificationOutboxWorker
import io.github.lightsmit.service.TelegramDeliveryFailureClassifier
import io.github.lightsmit.security.CryptoHelper
import io.github.lightsmit.service.TelegramUpdatePoller
import io.github.lightsmit.service.UserDataService
import io.github.lightsmit.storage.BotUserRepository
import io.github.lightsmit.storage.DatabaseManager
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.storage.SubscriptionRepository
import io.github.lightsmit.storage.TelegramUpdateStateRepository
import io.github.lightsmit.storage.UserSettingsRepository
import io.github.lightsmit.telegram.MailViewCallbackCodec
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

fun main() = runBlocking {
    // Фоновые задачи, запущенные из API (например, отправка вложений в чат)
    val appScope = this
    val logger = LoggerFactory.getLogger("io.github.lightsmit.Main")
    logger.info("Mail to Telegram Bot started")

    val telegramToken = Environment.require("TELEGRAM_BOT_TOKEN")
    // Владелец бота: всегда администратор
    val ownerChatId = Environment
        .get("TELEGRAM_CHAT_ID")
        ?.let { value -> value.toLongOrNull() ?: error("TELEGRAM_CHAT_ID must be a valid integer") }

    // Администраторы: ADMIN_IDS (через запятую) + владелец из TELEGRAM_CHAT_ID
    val adminChatIds = buildSet {
        Environment.get("ADMIN_IDS")
            ?.split(',', ' ', ';')
            ?.mapNotNull { value -> value.trim().takeIf(String::isNotEmpty) }
            ?.forEach { value -> add(value.toLongOrNull() ?: error("ADMIN_IDS must contain Telegram user IDs")) }
        ownerChatId?.let(::add)
    }

    val backupHour = Environment.get("BACKUP_HOUR")?.toIntOrNull() ?: 4
    val backupKeep = Environment.get("BACKUP_KEEP")?.toIntOrNull() ?: 7
    val backupSendToAdmins = Environment.get("BACKUP_SEND_TO_ADMINS")?.lowercase() != "false"
    // Ответ на письма из Telegram. Включать, только если хостинг пускает исходящий SMTP (порты 465/587)
    val replyEnabled = Environment.get("REPLY_ENABLED")?.lowercase() == "true"

    // Тарифы и оплата звездами. Пока BILLING_ENABLED не true, все пользуются ботом как раньше (лимит Pro)
    fun stars(name: String, default: Int): Int {
        val value = Environment.get(name)?.toIntOrNull() ?: default
        require(value in 1..10_000) { "$name must be between 1 and 10000 stars" }
        return value
    }
    val billingConfig = BillingConfig(
        enabled = Environment.get("BILLING_ENABLED")?.lowercase() == "true",
        trialDays = (Environment.get("TRIAL_DAYS")?.toIntOrNull() ?: 7).also { days ->
            require(days in 0..90) { "TRIAL_DAYS must be between 0 and 90" }
        },
        proMonthStars = stars("PRICE_PRO_MONTH", 100),
        proYearStars = stars("PRICE_PRO_YEAR", 1_000),
        businessMonthStars = stars("PRICE_BUSINESS_MONTH", 300),
        businessYearStars = stars("PRICE_BUSINESS_YEAR", 3_000),
        supportContact = Environment.get("SUPPORT_CONTACT")?.trim()?.takeIf(String::isNotEmpty),
    )

    val telegramProxyUrl = Environment.get("TELEGRAM_PROXY_URL")

    // Адрес Mini App для кнопок в сообщениях бота: MINI_APP_URL или https://DOMAIN
    val miniAppUrl = Environment.get("MINI_APP_URL")
        ?: Environment.get("DOMAIN")?.let { domain -> "https://$domain" }

    val databasePath = Path.of(
        Environment.get("DATABASE_PATH") ?: "data/mail-bot.db",
    )

    // Ключ шифрования обязателен: падаем сразу, а не при первом сохранении ящика
    CryptoHelper.requireConfigured()

    DatabaseManager.init(databasePath)

    val attachmentTempDirectory = Path.of(
        Environment.get("ATTACHMENT_TEMP_DIR") ?: "data/attachments",
    )

    val maxAttachmentSizeMb = Environment
        .get("MAX_ATTACHMENT_SIZE_MB")
        ?.toLongOrNull()
        ?: 45L
    require(maxAttachmentSizeMb in 1L..45L) {
        "MAX_ATTACHMENT_SIZE_MB must be between 1 and 45"
    }

    val reconnectDelaySeconds = Environment
        .get("IDLE_RECONNECT_DELAY_SECONDS")
        ?.toLongOrNull()
        ?: 5L
    require(reconnectDelaySeconds in 1L..300L) {
        "IDLE_RECONNECT_DELAY_SECONDS must be between 1 and 300"
    }

    val safetyPollSeconds = Environment
        .get("IMAP_SAFETY_POLL_SECONDS")
        ?.toLongOrNull()
        ?: 15L
    require(safetyPollSeconds in 5L..3600L) {
        "IMAP_SAFETY_POLL_SECONDS must be between 5 and 3600"
    }

    // Проверка ящика NOOP по открытому IDLE-соединению: ловит письма, о которых
    // сервер не сообщил по IDLE вовремя (0 - отключить)
    val noopIntervalSeconds = Environment
        .get("IMAP_NOOP_SECONDS")
        ?.toLongOrNull()
        ?: 5L
    require(noopIntervalSeconds == 0L || noopIntervalSeconds in 2L..300L) {
        "IMAP_NOOP_SECONDS must be 0 or between 2 and 300"
    }

    Files.createDirectories(attachmentTempDirectory)
    Files.list(attachmentTempDirectory).use { paths ->
        paths.forEach { path ->
            runCatching { Files.deleteIfExists(path) }
        }
    }

    val maxAttachmentSizeBytes = maxAttachmentSizeMb * 1024L * 1024L

    val telegramControlClient = TelegramClient(
        token = telegramToken,
        proxyUrl = telegramProxyUrl,
    )
    val telegramPollingClient = TelegramClient(
        token = telegramToken,
        proxyUrl = telegramProxyUrl,
    )
    val telegramMediaClient = TelegramClient(
        token = telegramToken,
        proxyUrl = telegramProxyUrl,
    )

    // Команды бота для подсказок в Telegram при вводе "/"
    launch {
        try {
            // По умолчанию русские подсказки, для английского Telegram - английские
            for ((languageCode, texts) in listOf(null to Lang.RU.texts, "en" to Lang.EN.texts)) {
                telegramControlClient.setMyCommands(
                    commands = botCommands(texts, billingEnabled = billingConfig.enabled),
                    languageCode = languageCode,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Telegram setMyCommands failed: {}", exception.javaClass.simpleName)
        }
    }

    // Диагностика: какому боту принадлежит токен. Mini App должен открываться
    // именно из этого бота, иначе проверка подписи initData не пройдет (401).
    launch {
        try {
            val username = telegramControlClient.getBotUsername()
            logger.info("Telegram bot for TELEGRAM_BOT_TOKEN: @{}", username)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Telegram getMe failed: {}", exception.javaClass.simpleName)
        }
    }

    val imapClient = ImapMailClient(
        attachmentTempDirectory = attachmentTempDirectory,
        maxAttachmentSizeBytes = maxAttachmentSizeBytes,
    )
    val contentLoader = EmailContentLoader(
        imapClient = imapClient,
        accountCode = MailViewCallbackCodec::accountCode,
    )

    val mailStateRepository = MailStateRepository()
    val mailOutboxRepository = MailNotificationOutboxRepository()
    val mailAccountRepository = MailAccountRepository()
    val botUserRepository = BotUserRepository()
    botUserRepository.backfillFromMailboxOwners()

    val accountRegistry = MailAccountRegistry()

    val userSettingsRepository = UserSettingsRepository()

    val subscriptionService = SubscriptionService(
        store = SubscriptionRepository(),
        config = billingConfig,
        adminIds = adminChatIds,
        cancelRenewal = { userId, chargeId -> telegramControlClient.editUserStarSubscription(userId, chargeId, canceled = true) },
        refundStars = { userId, chargeId -> telegramControlClient.refundStarPayment(userId, chargeId) },
    )
    val mailboxQuota = MailboxQuota(registry = accountRegistry, plan = subscriptionService::plan)
    val invoiceLinks = InvoiceLinks { offer, lang ->
        val billingTexts = lang.texts.billing
        telegramControlClient.createStarsInvoiceLink(
            title = billingTexts.invoiceTitle(offer),
            description = billingTexts.invoiceDescription(offer),
            payload = offer.payload,
            label = billingTexts.invoiceLabel,
            amount = billingConfig.price(offer),
            subscriptionPeriodSeconds = if (offer.recurring) SubscriptionService.SUBSCRIPTION_PERIOD_SECONDS else null,
        )
    }

    val forwardingService = MailForwardingService(
        registry = accountRegistry,
        imapClient = imapClient,
        telegramControlClient = telegramControlClient,
        telegramMediaClient = telegramMediaClient,
        stateRepository = mailStateRepository,
        outboxRepository = mailOutboxRepository,
        contentLoader = contentLoader,
        replyEnabled = replyEnabled,
        notificationSettings = { ownerId -> userSettingsRepository.get(ownerId) },
        language = botUserRepository::language,
        plan = subscriptionService::plan,
        isOverLimit = mailboxQuota::isOverLimit,
    )

    val healthMonitor = MailboxHealthMonitor(
        telegramClient = telegramControlClient,
        miniAppUrl = miniAppUrl,
        language = botUserRepository::language,
    )

    val idleWatcher = ImapIdleWatcher(
        onIdleMailboxChanged = forwardingService::processIdleAccount,
        onSafetyMailboxCheck = forwardingService::processAccount,
        reconnectDelaySeconds = reconnectDelaySeconds,
        safetyPollSeconds = safetyPollSeconds,
        noopIntervalSeconds = noopIntervalSeconds,
        onMailboxConnected = healthMonitor::onConnected,
        onMailboxFailure = healthMonitor::onFailure,
    )

    val mailboxSupervisor = MailboxSupervisor(
        watcher = idleWatcher,
        registry = accountRegistry,
    )

    val accountService = MailAccountService(
        repository = mailAccountRepository,
        supervisor = mailboxSupervisor,
        stateRepository = mailStateRepository,
        outboxRepository = mailOutboxRepository,
        imapClient = imapClient,
        mailboxLimit = mailboxQuota::limit,
        healthMonitor = healthMonitor,
    )

    // Ящики должны быть в реестре до запуска воркеров outbox, иначе ожидающие
    // уведомления посчитаются уведомлениями удаленных ящиков
    val watchedMailboxes = accountService.startAll()

    val backupService = BackupService(
        backupDirectory = (databasePath.toAbsolutePath().parent ?: Path.of(".")).resolve("backups"),
        telegramClient = telegramMediaClient,
        adminChatIds = adminChatIds,
        dailyHour = backupHour,
        keepCopies = backupKeep,
        sendToAdmins = backupSendToAdmins,
    )

    val adminService = AdminService(
        adminChatIds = adminChatIds,
        botUsers = botUserRepository,
        accounts = mailAccountRepository,
        outbox = mailOutboxRepository,
        healthMonitor = healthMonitor,
        backupService = backupService,
        telegramClient = telegramControlClient,
        subscriptions = subscriptionService,
    )

    val userDataService = UserDataService(
        accountService = accountService,
        settingsRepository = userSettingsRepository,
        botUsers = botUserRepository,
        backupKeepDays = backupKeep,
        subscriptions = subscriptionService,
    )

    val apiServer = ApiServer(
        port = Environment.get("API_PORT")?.toIntOrNull() ?: 8080,
        botToken = telegramToken,
        accountService = accountService,
        onUserSeen = { userId, languageCode -> botUserRepository.touch(userId, languageCode) },
        resolveLanguage = { userId, languageCode -> botUserRepository.resolve(userId, languageCode) },
        setLanguage = { userId, lang -> botUserRepository.setLanguage(userId, lang) },
        notificationSettings = NotificationSettingsService(
            repository = userSettingsRepository,
            outboxRepository = mailOutboxRepository,
        ),
        userData = userDataService,
        subscriptions = subscriptionService,
        invoiceLinks = invoiceLinks,
        quota = mailboxQuota,
        mailBrowser = MailBrowserService(
            repository = mailAccountRepository,
            imapClient = imapClient,
            // Отправка вложений может занять минуты: запускаем в фоне, приложение сразу получает ответ
            sendAttachmentsToChat = { account, uidValidity, uid ->
                appScope.launch {
                    runCatching { forwardingService.sendAttachments(account, uidValidity, uid) }
                }
            },
        ),
    )
    apiServer.start()

    val mailOutboxWorker = MailNotificationOutboxWorker(
        repository = mailOutboxRepository,
        failureClassifier = TelegramDeliveryFailureClassifier(),
        deliver = forwardingService::deliverOutboxNotification,
    )

    try {
        val telegramUpdatePoller = TelegramUpdatePoller(
            registry = accountRegistry,
            pollingClient = telegramPollingClient,
            controlClient = telegramControlClient,
            forwardingService = forwardingService,
            stateRepository = TelegramUpdateStateRepository(),
            miniAppUrl = miniAppUrl,
            accountService = accountService,
            botUsers = botUserRepository,
            adminService = adminService,
            userData = userDataService,
            subscriptions = subscriptionService,
            invoiceLinks = invoiceLinks,
            quota = mailboxQuota,
            replyService = if (replyEnabled) {
                MailReplyService(
                    registry = accountRegistry,
                    imapClient = imapClient,
                    smtpSender = SmtpMailSender(),
                )
            } else {
                null
            },
        )

        logger.info("Watched mailboxes: {}", watchedMailboxes)
        logger.info(
            "Billing: {}, trial {} days, Pro {}/{} stars, support contact: {}",
            if (billingConfig.enabled) "on" else "off",
            billingConfig.trialDays,
            billingConfig.proMonthStars,
            billingConfig.proYearStars,
            if (billingConfig.supportContact != null) "set" else "not set",
        )
        logger.info(
            "IMAP safety poll: {} s, NOOP: {} s, max attachment: {} MB, Telegram proxy: {}, replies: {}",
            safetyPollSeconds,
            noopIntervalSeconds,
            maxAttachmentSizeMb,
            if (telegramProxyUrl.isNullOrBlank()) "off" else "on",
            if (replyEnabled) "on" else "off",
        )
        logger.info("Admins: {}, daily backup at {}:00, keeping {} copies", adminChatIds.size, backupHour, backupKeep)
        logger.info("Waiting for new emails...")

        supervisorScope {
            val telegramUpdateJob = launch {
                telegramUpdatePoller.run()
            }

            // 5 параллельных воркеров доставки уведомлений
            val mailOutboxJobs = (1..5).map {
                launch { mailOutboxWorker.run() }
            }

            // Ежедневный бэкап базы (BACKUP_HOUR по времени сервера)
            val backupJob = launch {
                backupService.runSchedule()
            }

            // Напоминания о конце подписки и сообщения о переходе на Free
            val subscriptionJob = launch {
                subscriptionService.runSchedule { notice ->
                    val billingTexts = botUserRepository.language(notice.userId).texts.billing
                    val text = when (notice) {
                        is SubscriptionNotice.Expiring -> billingTexts.expiring(notice.plan, notice.expiresAt, notice.trial)
                        is SubscriptionNotice.Ended -> billingTexts.ended(notice.plan, notice.trial)
                    }
                    telegramControlClient.sendMessage(chatId = notice.userId, text = text)
                }
            }

            (listOf(telegramUpdateJob, backupJob, subscriptionJob) + mailOutboxJobs).joinAll()
        }
    } finally {
        mailboxSupervisor.close()
        healthMonitor.close()
        forwardingService.close()
        contentLoader.close()
        telegramMediaClient.close()
        telegramPollingClient.close()
        telegramControlClient.close()
    }
}