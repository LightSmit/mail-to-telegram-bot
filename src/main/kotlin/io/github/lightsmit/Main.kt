package io.github.lightsmit

import io.github.lightsmit.config.Environment
import io.github.lightsmit.config.MailAccountConfigLoader
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.service.MailForwardingService
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main() = runBlocking {
    println("Mail to Telegram Bot started")

    val telegramToken =
        Environment.require("TELEGRAM_BOT_TOKEN")

    val telegramChatId = Environment
        .require("TELEGRAM_CHAT_ID")
        .toLongOrNull()
        ?: error("TELEGRAM_CHAT_ID must be a valid integer")

    val pollIntervalSeconds = Environment
        .get("POLL_INTERVAL_SECONDS")
        ?.toLongOrNull()
        ?: 30L

    require(pollIntervalSeconds in 10L..3600L) {
        "POLL_INTERVAL_SECONDS must be between 10 and 3600"
    }

    val databasePath = Path.of(
        Environment.get("DATABASE_PATH")
            ?: "data/mail-bot.db",
    )

    val accounts = MailAccountConfigLoader.load()
    val telegramClient = TelegramClient(telegramToken)

    try {
        val forwardingService = MailForwardingService(
            accounts = accounts,
            imapClient = ImapMailClient(),
            telegramClient = telegramClient,
            telegramChatId = telegramChatId,
            stateRepository = MailStateRepository(databasePath),
        )

        println("Configured mailboxes: ${accounts.size}")
        println("Polling interval: $pollIntervalSeconds seconds")
        println("Waiting for new emails...")

        while (currentCoroutineContext().isActive) {
            forwardingService.pollOnce()
            delay(pollIntervalSeconds * 1_000)
        }
    } finally {
        telegramClient.close()
    }
}