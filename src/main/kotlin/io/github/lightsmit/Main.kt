package io.github.lightsmit

import io.github.lightsmit.api.ApiServer
import io.github.lightsmit.config.Environment
import io.github.lightsmit.config.MailAccountConfigLoader
import io.github.lightsmit.mail.ImapIdleWatcher
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.service.EmailContentLoader
import io.github.lightsmit.service.MailAccountRegistry
import io.github.lightsmit.service.MailAccountService
import io.github.lightsmit.service.MailboxHealthMonitor
import io.github.lightsmit.service.MailboxSupervisor
import io.github.lightsmit.service.MailForwardingService
import io.github.lightsmit.service.MailNotificationOutboxWorker
import io.github.lightsmit.service.TelegramDeliveryFailureClassifier
import io.github.lightsmit.security.CryptoHelper
import io.github.lightsmit.service.TelegramUpdatePoller
import io.github.lightsmit.storage.DatabaseManager
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.storage.TelegramUpdateStateRepository
import io.github.lightsmit.telegram.MailViewCallbackCodec
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import java.nio.file.Files
import java.nio.file.Path

fun main() = runBlocking {
    println("Mail to Telegram Bot started")

    val telegramToken = Environment.require("TELEGRAM_BOT_TOKEN")
    // Нужен только для разового импорта ящиков из .env (MAIL_ACCOUNT_*) на этот Telegram ID
    val legacyOwnerChatId = Environment
        .get("TELEGRAM_CHAT_ID")
        ?.let { value -> value.toLongOrNull() ?: error("TELEGRAM_CHAT_ID must be a valid integer") }

    val maxAccountsPerOwner = Environment
        .get("MAX_MAILBOXES_PER_USER")
        ?.toIntOrNull()
        ?: 5
    require(maxAccountsPerOwner in 1..100) {
        "MAX_MAILBOXES_PER_USER must be between 1 and 100"
    }

    val telegramProxyUrl = Environment.get("TELEGRAM_PROXY_URL")

    // Адрес Mini App для кнопок в сообщениях бота: MINI_APP_URL или https://DOMAIN
    val miniAppUrl = Environment.get("MINI_APP_URL")
        ?: Environment.get("DOMAIN")?.let { domain -> "https://$domain" }

    val databasePath = Path.of(
        Environment.get("DATABASE_PATH") ?: "data/mail-bot.db",
    )

    // Ключ шифрования обязателен: падаем сразу, а не при первом сохранении ящика
    CryptoHelper.requireConfigured()

    // ИНИЦИАЛИЗАЦИЯ БАЗЫ ДАННЫХ HIKARICP
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
        ?: Environment
            .get("IDLE_FALLBACK_POLL_SECONDS")
            ?.toLongOrNull()
        ?: 15L
    require(safetyPollSeconds in 5L..3600L) {
        "IMAP_SAFETY_POLL_SECONDS must be between 5 and 3600"
    }

    Files.createDirectories(attachmentTempDirectory)
    Files.list(attachmentTempDirectory).use { paths ->
        paths.forEach { path ->
            runCatching { Files.deleteIfExists(path) }
        }
    }

    val legacyAccounts = MailAccountConfigLoader.load()
    // ИСПРАВЛЕНА ОПЕЧАТКА С УМНОЖЕНИЕМ
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

    // Диагностика: какому боту принадлежит токен. Mini App должен открываться
    // именно из этого бота, иначе проверка подписи initData не пройдёт (401).
    launch {
        try {
            val username = telegramControlClient.getBotUsername()
            println("Telegram bot for TELEGRAM_BOT_TOKEN: @$username")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            println("Telegram getMe failed: ${exception.javaClass.simpleName}")
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

    // РЕПОЗИТОРИЯМ БОЛЬШЕ НЕ НУЖЕН ПУТЬ К БАЗЕ
    val mailStateRepository = MailStateRepository()
    val mailOutboxRepository = MailNotificationOutboxRepository()
    val mailAccountRepository = MailAccountRepository()

    val accountRegistry = MailAccountRegistry()

    val forwardingService = MailForwardingService(
        registry = accountRegistry,
        imapClient = imapClient,
        telegramControlClient = telegramControlClient,
        telegramMediaClient = telegramMediaClient,
        stateRepository = mailStateRepository,
        outboxRepository = mailOutboxRepository,
        contentLoader = contentLoader,
    )

    val healthMonitor = MailboxHealthMonitor(
        telegramClient = telegramControlClient,
        miniAppUrl = miniAppUrl,
    )

    val idleWatcher = ImapIdleWatcher(
        onIdleMailboxChanged = forwardingService::processIdleAccount,
        onSafetyMailboxCheck = forwardingService::processAccount,
        reconnectDelaySeconds = reconnectDelaySeconds,
        safetyPollSeconds = safetyPollSeconds,
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
        maxAccountsPerOwner = maxAccountsPerOwner,
        healthMonitor = healthMonitor,
    )

    // Разовый перенос ящиков из .env в базу (повторный запуск ничего не дублирует)
    if (legacyAccounts.isNotEmpty()) {
        val owner = legacyOwnerChatId
            ?: error("TELEGRAM_CHAT_ID is required to import MAIL_ACCOUNT_* mailboxes from .env")
        val imported = accountService.importLegacyAccounts(legacyAccounts, owner)
        println("Mailboxes from .env imported into the database: $imported of ${legacyAccounts.size}")
        println("Remove MAIL_ACCOUNT_* from .env: mailboxes are now managed in the Mini App")
    }

    // Ящики должны быть в реестре до запуска воркеров outbox, иначе ожидающие
    // уведомления посчитаются уведомлениями удалённых ящиков
    val watchedMailboxes = accountService.startAll()

    val apiServer = ApiServer(
        port = Environment.get("API_PORT")?.toIntOrNull() ?: 8080,
        botToken = telegramToken,
        accountService = accountService,
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
        )

        println("Watched mailboxes: $watchedMailboxes (limit per user: $maxAccountsPerOwner)")
        println("Maximum attachment size: $maxAttachmentSizeMb MB")
        println("Real-time IMAP IDLE monitoring enabled")
        println("IMAP safety polling interval: $safetyPollSeconds seconds")
        println(
            if (telegramProxyUrl.isNullOrBlank()) {
                "Telegram proxy: disabled"
            } else {
                "Telegram proxy: enabled"
            },
        )
        println("Persistent notification outbox worker enabled")
        println("Waiting for new emails...")

        supervisorScope {
            val telegramUpdateJob = launch {
                telegramUpdatePoller.run()
            }

            // 5 параллельных воркеров доставки уведомлений
            val mailOutboxJobs = (1..5).map {
                launch { mailOutboxWorker.run() }
            }

            (listOf(telegramUpdateJob) + mailOutboxJobs).joinAll()
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