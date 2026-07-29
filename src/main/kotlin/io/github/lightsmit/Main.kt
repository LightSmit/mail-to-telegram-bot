package io.github.lightsmit

import io.github.lightsmit.config.Environment
import io.github.lightsmit.config.MailAccountConfigLoader
import io.github.lightsmit.mail.ImapIdleWatcher
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.service.MailForwardingService
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import java.nio.file.Path

fun main() = runBlocking {
    println("Mail to Telegram Bot started")

    val telegramToken =
        Environment.require("TELEGRAM_BOT_TOKEN")

    val telegramChatId = Environment
        .require("TELEGRAM_CHAT_ID")
        .toLongOrNull()
        ?: error(
            "TELEGRAM_CHAT_ID must be a valid integer",
        )

    val databasePath = Path.of(
        Environment.get("DATABASE_PATH")
            ?: "data/mail-bot.db",
    )

    val attachmentTempDirectory = Path.of(
        Environment.get("ATTACHMENT_TEMP_DIR")
            ?: "data/attachments",
    )

    val maxAttachmentSizeMb = Environment
        .get("MAX_ATTACHMENT_SIZE_MB")
        ?.toLongOrNull()
        ?: 45L

    require(maxAttachmentSizeMb in 1L..45L) {
        "MAX_ATTACHMENT_SIZE_MB must be between 1 and 45"
    }

    val maxAttachmentSizeBytes =
        maxAttachmentSizeMb * 1024L * 1024L

    val reconnectDelaySeconds = Environment
        .get("IDLE_RECONNECT_DELAY_SECONDS")
        ?.toLongOrNull()
        ?: 5L

    require(reconnectDelaySeconds in 1L..300L) {
        "IDLE_RECONNECT_DELAY_SECONDS " +
                "must be between 1 and 300"
    }

    val fallbackPollSeconds = Environment
        .get("IDLE_FALLBACK_POLL_SECONDS")
        ?.toLongOrNull()
        ?: 10L

    require(fallbackPollSeconds in 5L..3600L) {
        "IDLE_FALLBACK_POLL_SECONDS " +
                "must be between 5 and 3600"
    }

    val accounts = MailAccountConfigLoader.load()
    val telegramClient = TelegramClient(telegramToken)

    try {
        val forwardingService =
            MailForwardingService(
                imapClient = ImapMailClient(
                    attachmentTempDirectory =
                        attachmentTempDirectory,

                    maxAttachmentSizeBytes =
                        maxAttachmentSizeBytes,
                ),

                telegramClient = telegramClient,
                telegramChatId = telegramChatId,

                stateRepository =
                    MailStateRepository(databasePath),
            )

        val idleWatcher = ImapIdleWatcher(
            onIdleMailboxChanged =
                forwardingService::processIdleAccount,

            onFallbackMailboxChanged =
                forwardingService::processAccount,

            reconnectDelaySeconds =
                reconnectDelaySeconds,

            fallbackPollSeconds =
                fallbackPollSeconds,
        )

        println("Configured mailboxes: ${accounts.size}")
        println(
            "Maximum attachment size: " +
                    "$maxAttachmentSizeMb MB",
        )
        println("Real-time IMAP IDLE monitoring enabled")
        println(
            "Fallback polling interval: " +
                    "$fallbackPollSeconds seconds",
        )
        println("Waiting for new emails...")

        supervisorScope {
            accounts
                .map { account ->
                    launch {
                        idleWatcher.watch(account)
                    }
                }
                .joinAll()
        }
    } finally {
        telegramClient.close()
    }
}