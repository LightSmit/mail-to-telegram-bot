package io.github.lightsmit

import io.github.lightsmit.config.MailAccountConfigLoader
import io.github.lightsmit.mail.ImapMailClient
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun main() = runBlocking {
    println("Mail to Telegram Bot started")

    val accounts = MailAccountConfigLoader.load()
    val imapClient = ImapMailClient()

    val dateFormatter = DateTimeFormatter
        .ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    for (account in accounts) {
        println()
        println("Mailbox: ${account.name}")
        println("Address: ${account.username}")

        val messages = imapClient.fetchLatest(
            account = account,
            limit = 5,
        )

        if (messages.isEmpty()) {
            println("The mailbox is empty")
            continue
        }

        messages.forEachIndexed { index, message ->
            val sentAt = message.sentAt
                ?.let(dateFormatter::format)
                ?: "(date unknown)"

            println()
            println("Message ${index + 1}")
            println("UID: ${message.uid}")
            println("From: ${message.from}")
            println("Subject: ${message.subject}")
            println("Date: $sentAt")
        }
    }

    println()
    println("Mail connection is working")
}