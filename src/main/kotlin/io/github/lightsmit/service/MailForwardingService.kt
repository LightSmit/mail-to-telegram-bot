package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.mail.EmailSummary
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.telegram.TelegramClient
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import jakarta.mail.MessagingException
import kotlinx.coroutines.delay
import java.nio.file.Files

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
                processAccountWithRetry(account)
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

    private suspend fun processAccountWithRetry(
        account: MailAccountConfig,
    ) {
        val maximumAttempts = 3

        for (attempt in 1..maximumAttempts) {
            try {
                processAccount(account)
                return
            } catch (exception: MessagingException) {
                if (attempt == maximumAttempts) {
                    throw exception
                }

                val retryDelayMillis =
                    attempt * 2_000L

                logger.warn(
                    "IMAP operation failed for {}. " +
                            "Retry {}/{} in {} ms",
                    account.username,
                    attempt + 1,
                    maximumAttempts,
                    retryDelayMillis,
                    exception,
                )

                delay(retryDelayMillis)
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
            try {
                telegramClient.sendLongMessage(
                    chatId = telegramChatId,
                    text = formatMessage(
                        account = account,
                        message = message,
                    ),
                )

                for (attachment in message.attachments) {
                    telegramClient.sendDocument(
                        chatId = telegramChatId,
                        attachment = attachment,
                    )
                }

                if (message.skippedAttachments.isNotEmpty()) {
                    telegramClient.sendLongMessage(
                        chatId = telegramChatId,
                        text = formatSkippedAttachments(
                            account = account,
                            message = message,
                        ),
                    )
                }

                stateRepository.save(
                    accountKey = accountKey,
                    uidValidity = batch.uidValidity,
                    lastUid = message.uid,
                )

                logger.git status
                        git check-ignore -v .env
                git check-ignore -v data/attachmentsinfo(
                    "Forwarded email UID {} from mailbox {} " +
                            "with {} attachment(s)",
                    message.uid,
                    account.username,
                    message.attachments.size,
                )
            } finally {
                deleteTemporaryAttachments(message)
            }
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

        val body = message.body
            ?.takeIf(String::isNotBlank)
            ?: "(текст письма отсутствует или не удалось распознать)"

        return buildString {
            appendLine("📨 Новое письмо")
            appendLine()
            appendLine("Ящик: ${account.name}")
            appendLine("Адрес: ${account.username}")
            appendLine("От: ${message.from}")
            appendLine("Тема: ${message.subject}")
            appendLine("Дата: $sentAt")
            appendLine()
            appendLine("Текст:")
            append(body)

            if (message.attachments.isNotEmpty()) {
                appendLine()
                appendLine()
                append(
                    "Вложения: ${message.attachments.size}",
                )
            }

            if (message.skippedAttachments.isNotEmpty()) {
                appendLine()
                appendLine()
                append(
                    "Пропущено вложений: " +
                            message.skippedAttachments.size,
                )
            }
        }
    }

    private fun formatSkippedAttachments(
        account: MailAccountConfig,
        message: EmailSummary,
    ): String {
        return buildString {
            appendLine("⚠️ Некоторые вложения не были отправлены")
            appendLine()
            appendLine("Ящик: ${account.name}")
            appendLine("Тема: ${message.subject}")
            appendLine()

            message.skippedAttachments
                .forEachIndexed { index, attachment ->
                    append(
                        "${index + 1}. " +
                                "${attachment.fileName}: " +
                                attachment.reason,
                    )

                    if (
                        index <
                        message.skippedAttachments.lastIndex
                    ) {
                        appendLine()
                    }
                }
        }
    }

    private fun deleteTemporaryAttachments(
        message: EmailSummary,
    ) {
        message.attachments.forEach { attachment ->
            try {
                Files.deleteIfExists(
                    attachment.tempFile,
                )
            } catch (exception: Exception) {
                logger.warn(
                    "Failed to delete temporary attachment {}",
                    attachment.tempFile,
                    exception,
                )
            }
        }
    }

    private fun buildAccountKey(
        account: MailAccountConfig,
    ): String {
        return "${account.host}:${account.username}"
            .lowercase()
    }
}