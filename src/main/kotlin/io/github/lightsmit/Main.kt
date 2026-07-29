package io.github.lightsmit

import io.github.lightsmit.config.Environment
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("Mail to Telegram Bot started")

    val botToken = Environment.require("TELEGRAM_BOT_TOKEN")

    val configuredChatId = Environment
        .get("TELEGRAM_CHAT_ID")
        ?.toLongOrNull()

    val telegramClient = TelegramClient(botToken)

    try {
        val chatId = configuredChatId
            ?: telegramClient.findLatestPrivateChatId()
            ?: error(
                "Telegram chat was not found. " +
                        "Send /start to the bot and run the application again.",
            )

        telegramClient.sendMessage(
            chatId = chatId,
            text = "Mail to Telegram Bot успешно подключен",
        )

        println("Telegram connection is working")
        println("TELEGRAM_CHAT_ID=$chatId")
    } finally {
        telegramClient.close()
    }
}