package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.mail.EmailSummary
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.telegram.TelegramClient
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MailForwardingService(
    private val accounts: List<MailAccountConfig>,
    private val imapClient: ImapMailClient,
    private val telegramClient: TelegramClient,
    private val telegramChatId: Long,
    private val stateRepository: MailStateRepository,
) {

    private val logger =
        LoggerFactory.getLogger(MailForwardingService::class.java)

    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    suspend fun pollOnce() {
        for (account in accounts) {
            try {
                processAccount(account)
            } catch (exception: Exception) {
                logger.error(
                    "Failed to process mailbox {} ({})",
                    account.name,
                    account.username,
                    exception,
                )
            }
        }
    }

    private suspend fun processAccount(
        account: MailAccountConfig,
    ) {
        val accountKey = buildAccountKey(account)
        val state = stateRepository.find(accountKey)

        if (state == null) {
            initializeAccount(account, accountKey)
            return
        }

        val batch = imapClient.fetchAfterUid(
            account = account,
            afterUid = state.lastUid,
            limit = 50,
        )

        if (batch.uidValidity != state.uidValidity) {
            stateRepository.save(
                accountKey = accountKey,
                uidValidity = batch.uidValidity,
                lastUid = batch.latestUid,
            )

            telegramClient.sendMessage(
                chatId = telegramChatId,
                text = buildString {
                    appendLine("⚠️ Нумерация писем была обновлена")
                    appendLine()
                    appendLine("Ящик: ${account.name}")
                    append("Адрес: ${account.username}")
                },
            )

            logger.warn(
                "UIDVALIDITY changed for mailbox {}",
                account.username,
            )

            return
        }

        for (message in batch.messages) {
            telegramClient.sendMessage(
                chatId = telegramChatId,
                text = formatMessage(account, message),
            )

            stateRepository.save(
                accountKey = accountKey,
                uidValidity = batch.uidValidity,
                lastUid = message.uid,
            )

            logger.info(
                "Forwarded email UID {} from mailbox {}",
                message.uid,
                account.username,
            )
        }
    }

    private suspend fun initializeAccount(
        account: MailAccountConfig,
        accountKey: String,
    ) {
        val cursor = imapClient.fetchCursor(account)

        stateRepository.save(
            accountKey = accountKey,
            uidValidity = cursor.uidValidity,
            lastUid = cursor.latestUid,
        )

        telegramClient.sendMessage(
            chatId = telegramChatId,
            text = buildString {
                appendLine("✅ Почтовый ящик подключён")
                appendLine()
                appendLine("Название: ${account.name}")
                appendLine("Адрес: ${account.username}")
                append("Старые письма пересылаться не будут.")
            },
        )

        logger.info(
            "Initialized mailbox {} at UID {}",
            account.username,
            cursor.latestUid,
        )
    }

    private fun formatMessage(
        account: MailAccountConfig,
        message: EmailSummary,
    ): String {
        val sentAt = message.sentAt
            ?.let(dateFormatter::format)
            ?: "(дата неизвестна)"

        return buildString {
            appendLine("📨 Новое письмо")
            appendLine()
            appendLine("Ящик: ${account.name}")
            appendLine("Адрес: ${account.username}")
            appendLine("От: ${message.from}")
            appendLine("Тема: ${message.subject}")
            append("Дата: $sentAt")
        }
    }

    private fun buildAccountKey(
        account: MailAccountConfig,
    ): String {
        return "${account.host}:${account.username}"
            .lowercase()
    }
}