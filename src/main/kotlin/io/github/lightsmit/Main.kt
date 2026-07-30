package io.github.lightsmit

import io.github.lightsmit.config.Environment
import io.github.lightsmit.config.MailAccountConfigLoader
import io.github.lightsmit.mail.ImapIdleWatcher
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.service.EmailContentLoader
import io.github.lightsmit.service.MailForwardingService
import io.github.lightsmit.service.MailNotificationOutboxWorker
import io.github.lightsmit.service.TelegramDeliveryFailureClassifier
import io.github.lightsmit.service.TelegramUpdatePoller
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.storage.TelegramUpdateStateRepository
import io.github.lightsmit.telegram.MailViewCallbackCodec
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import java.nio.file.Files
import java.nio.file.Path

fun main() = runBlocking {
    println("Mail to Telegram Bot started")

    val telegramToken = Environment.require("TELEGRAM_BOT_TOKEN")
    val telegramChatId = Environment
        .require("TELEGRAM_CHAT_ID")
        .toLongOrNull()
        ?: error("TELEGRAM_CHAT_ID must be a valid integer")

    val telegramProxyUrl = Environment.get("TELEGRAM_PROXY_URL")

    val databasePath = Path.of(
        Environment.get("DATABASE_PATH") ?: "data/mail-bot.db",
    )
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

    val accounts = MailAccountConfigLoader.load()
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

    val imapClient = ImapMailClient(
        attachmentTempDirectory = attachmentTempDirectory,
        maxAttachmentSizeBytes = maxAttachmentSizeBytes,
    )
    val contentLoader = EmailContentLoader(
        imapClient = imapClient,
        accountCode = MailViewCallbackCodec::accountCode,
    )

    val mailStateRepository =
        MailStateRepository(databasePath)

    val mailOutboxRepository =
        MailNotificationOutboxRepository(databasePath)

    val forwardingService = MailForwardingService(
        accounts = accounts,
        imapClient = imapClient,
        telegramControlClient = telegramControlClient,
        telegramMediaClient = telegramMediaClient,
        telegramChatId = telegramChatId,
        stateRepository = mailStateRepository,
        outboxRepository = mailOutboxRepository,
        contentLoader = contentLoader,
    )

    val mailOutboxWorker = MailNotificationOutboxWorker(
        repository = mailOutboxRepository,
        failureClassifier = TelegramDeliveryFailureClassifier(),
        deliver = forwardingService::deliverOutboxNotification,
    )

    try {
        val idleWatcher = ImapIdleWatcher(
            onIdleMailboxChanged = forwardingService::processIdleAccount,
            onSafetyMailboxCheck = forwardingService::processAccount,
            reconnectDelaySeconds = reconnectDelaySeconds,
            safetyPollSeconds = safetyPollSeconds,
        )

        val telegramUpdatePoller = TelegramUpdatePoller(
            accounts = accounts,
            telegramChatId = telegramChatId,
            pollingClient = telegramPollingClient,
            controlClient = telegramControlClient,
            forwardingService = forwardingService,
            stateRepository = TelegramUpdateStateRepository(databasePath),
        )

        println("Configured mailboxes: ${accounts.size}")
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
            val mailWatcherJobs = accounts.map { account ->
                launch { idleWatcher.watch(account) }
            }
            val telegramUpdateJob = launch {
                telegramUpdatePoller.run()
            }

            val mailOutboxJob = launch {
                mailOutboxWorker.run()
            }

            (
                    mailWatcherJobs +
                            telegramUpdateJob +
                            mailOutboxJob
                    ).joinAll()
        }
    } finally {
        forwardingService.close()
        contentLoader.close()
        telegramMediaClient.close()
        telegramPollingClient.close()
        telegramControlClient.close()
    }
}
