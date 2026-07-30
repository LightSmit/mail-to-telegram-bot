package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.mail.EmailSummary
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxItem
import io.github.lightsmit.storage.MailOutboxOperation
import io.github.lightsmit.storage.MailStateRepository
import io.github.lightsmit.telegram.MailViewAction
import io.github.lightsmit.telegram.MailViewCallbackCodec
import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramClient
import io.github.lightsmit.telegram.TelegramInlineButton
import jakarta.mail.MessagingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.angus.mail.imap.IMAPFolder
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class MailForwardingService(
    accounts: List<MailAccountConfig>,
    private val imapClient: ImapMailClient,
    private val telegramControlClient: TelegramClient,
    private val telegramMediaClient: TelegramClient,
    private val telegramChatId: Long,
    private val stateRepository: MailStateRepository,
    private val outboxRepository: MailNotificationOutboxRepository,
    private val contentLoader: EmailContentLoader,
) : AutoCloseable {

    private val logger =
        LoggerFactory.getLogger(MailForwardingService::class.java)

    private val accountsByKey =
        accounts.associateBy(::buildAccountKey)

    init {
        require(accounts.isNotEmpty()) {
            "At least one mail account is required"
        }

        require(accountsByKey.size == accounts.size) {
            "Mail account host and username combinations must be unique"
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    private val navigationMutex = Mutex()
    private val accountProcessingLocks =
        ConcurrentHashMap<String, Mutex>()

    private val textViewMessageIds =
        ConcurrentHashMap<String, List<Long>>()

    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    suspend fun deliverOutboxNotification(
        item: MailOutboxItem,
    ): Long {
        if (
            item.operation !=
            MailOutboxOperation.SEND_NOTIFICATION
        ) {
            throw PermanentMailDeliveryException(
                "Unsupported outbox operation: ${item.operation}",
            )
        }

        val account = accountsByKey[item.accountKey]
            ?: throw PermanentMailDeliveryException(
                "Configured mail account no longer exists: " + item.accountKey,
            )

        val actualAccountCode =
            MailViewCallbackCodec.accountCode(account)

        if (actualAccountCode != item.accountCode) {
            throw PermanentMailDeliveryException(
                "Mail account code does not match the current configuration",
            )
        }

        val message = contentLoader.get(
            account = account,
            uidValidity = item.uidValidity,
            uid = item.uid,
        ) ?: throw PermanentMailDeliveryException(
            "Email UID ${item.uid} is no longer available " + "in mailbox ${account.username}",
        )

        val telegramMessageId =
            telegramControlClient.sendMessageWithButtons(
                chatId = telegramChatId,
                text = formatNotification(
                    account = account,
                    message = message,
                    attachmentsKnown = true,
                ),
                buttons = summaryButtons(
                    account = account,
                    uidValidity = item.uidValidity,
                    uid = item.uid,
                ),
            )

        logger.info(
            "Delivered outbox item {} for email UID {} from mailbox {}",
            item.id,
            item.uid,
            account.username,
        )

        return telegramMessageId
    }

    suspend fun processAccount(
        account: MailAccountConfig,
    ) {
        withAccountProcessingLock(account) {
            processAccountWithRetry(account)
        }
    }

    suspend fun processIdleAccount(
        account: MailAccountConfig,
        inbox: IMAPFolder,
    ) {
        withAccountProcessingLock(account) {
            processAccountInternal(
                account = account,
                idleInbox = inbox,
            )
        }
    }

    suspend fun openTextView(
        account: MailAccountConfig,
        expectedUidValidity: Long,
        uid: Long,
        sourceMessageId: Long,
    ) {
        val message = loadEmailOrNotify(
            account = account,
            expectedUidValidity = expectedUidValidity,
            uid = uid,
        ) ?: return

        navigationMutex.withLock {
            val newMessageIds = telegramControlClient
                .sendLongMessageWithButtons(
                    chatId = telegramChatId,
                    text = formatFullMessage(
                        account = account,
                        message = message,
                    ),
                    buttons = textViewButtons(
                        account = account,
                        uidValidity = expectedUidValidity,
                        uid = uid,
                    ),
                )

            val emailKey = interactiveEmailKey(
                account = account,
                uidValidity = expectedUidValidity,
                uid = uid,
            )

            textViewMessageIds[emailKey] = newMessageIds
            deleteMessageSafely(sourceMessageId)
        }

        logger.info(
            "Displayed text view for email UID {} from mailbox {}",
            uid,
            account.username,
        )
    }

    suspend fun returnToSummary(
        account: MailAccountConfig,
        expectedUidValidity: Long,
        uid: Long,
        sourceMessageId: Long,
    ) {
        val message = loadEmailOrNotify(
            account = account,
            expectedUidValidity = expectedUidValidity,
            uid = uid,
        ) ?: return

        navigationMutex.withLock {
            telegramControlClient.sendMessageWithButtons(
                chatId = telegramChatId,
                text = formatNotification(
                    account = account,
                    message = message,
                    attachmentsKnown = true,
                ),
                buttons = summaryButtons(
                    account = account,
                    uidValidity = expectedUidValidity,
                    uid = uid,
                ),
            )

            val emailKey = interactiveEmailKey(
                account = account,
                uidValidity = expectedUidValidity,
                uid = uid,
            )

            val messageIdsToDelete = buildSet {
                add(sourceMessageId)
                addAll(
                    textViewMessageIds.remove(emailKey)
                        .orEmpty(),
                )
            }

            messageIdsToDelete.forEach { messageId ->
                deleteMessageSafely(messageId)
            }
        }

        logger.info(
            "Returned email UID {} from mailbox {} to summary view",
            uid,
            account.username,
        )
    }

    suspend fun sendAttachments(
        account: MailAccountConfig,
        expectedUidValidity: Long,
        uid: Long,
    ) {
        val message = loadEmailOrNotify(
            account = account,
            expectedUidValidity = expectedUidValidity,
            uid = uid,
        ) ?: return

        if (
            message.attachments.isEmpty() &&
            message.skippedAttachments.isEmpty()
        ) {
            telegramControlClient.sendMessage(
                chatId = telegramChatId,
                text = "У этого письма нет вложений.",
            )
            return
        }

        for (attachment in message.attachments) {
            val startedAt = System.currentTimeMillis()

            telegramMediaClient.sendAttachment(
                chatId = telegramChatId,
                attachment = attachment,
            )

            logger.info(
                "Sent attachment {} of email UID {} in {} ms",
                attachment.fileName,
                uid,
                System.currentTimeMillis() - startedAt,
            )
        }

        if (message.skippedAttachments.isNotEmpty()) {
            telegramControlClient.sendLongMessage(
                chatId = telegramChatId,
                text = formatSkippedAttachments(
                    account = account,
                    message = message,
                ),
            )
        }

        logger.info(
            "Displayed attachments for email UID {} from mailbox {}: {} file(s)",
            uid,
            account.username,
            message.attachments.size,
        )
    }

    private suspend fun loadEmailOrNotify(
        account: MailAccountConfig,
        expectedUidValidity: Long,
        uid: Long,
    ): EmailSummary? {
        val message = contentLoader.get(
            account = account,
            uidValidity = expectedUidValidity,
            uid = uid,
        )

        if (message != null) {
            return message
        }

        telegramControlClient.sendMessage(
            chatId = telegramChatId,
            text = buildString {
                appendLine("⚠️ Не удалось открыть письмо")
                appendLine()
                append(
                    "Письмо могло быть удалено или перемещено " + "из папки «Входящие».",
                )
            },
        )

        return null
    }

    private suspend fun processAccountWithRetry(
        account: MailAccountConfig,
    ) {
        val maximumAttempts = 3

        for (attempt in 1..maximumAttempts) {
            try {
                processAccountInternal(
                    account = account,
                    idleInbox = null,
                )
                return
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: MessagingException) {
                if (attempt == maximumAttempts) {
                    throw exception
                }
                delay(attempt * 2_000L)
            }
        }
    }

    private suspend fun processAccountInternal(
        account: MailAccountConfig,
        idleInbox: IMAPFolder?,
    ) {
        val accountKey = buildAccountKey(account)
        val state = stateRepository.find(accountKey)

        if (state == null) {
            initializeAccount(
                account = account,
                accountKey = accountKey,
                idleInbox = idleInbox,
            )
            return
        }

        val batch = if (idleInbox != null) {
            imapClient.fetchAfterUid(
                account = account,
                inbox = idleInbox,
                afterUid = state.lastUid,
                limit = 100,
            )
        } else {
            imapClient.fetchAfterUid(
                account = account,
                afterUid = state.lastUid,
                limit = 100,
            )
        }

        if (batch.uidValidity != state.uidValidity) {
            stateRepository.save(
                accountKey = accountKey,
                uidValidity = batch.uidValidity,
                lastUid = batch.latestUid,
            )

            scope.launch {
                runCatching {
                    telegramControlClient.sendMessage(
                        chatId = telegramChatId,
                        text = buildString {
                            appendLine("⚠️ Нумерация писем была обновлена")
                            appendLine()
                            appendLine("Ящик: ${account.name}")
                            append("Адрес: ${account.username}")
                        },
                    )
                }
            }
            return
        }

        val accountCode =
            MailViewCallbackCodec.accountCode(account)

        var lastScheduledUid = state.lastUid

        for (message in batch.messages.sortedBy { item -> item.uid }) {
            if (message.uid <= lastScheduledUid) {
                continue
            }

            logDeliveryTiming(
                account = account,
                message = message,
            )

            val inserted = outboxRepository.enqueue(
                accountKey = accountKey,
                accountCode = accountCode,
                uidValidity = batch.uidValidity,
                uid = message.uid,
            )

            lastScheduledUid = message.uid

            stateRepository.save(
                accountKey = accountKey,
                uidValidity = batch.uidValidity,
                lastUid = lastScheduledUid,
            )

            contentLoader.prefetch(
                account = account,
                uidValidity = batch.uidValidity,
                uid = message.uid,
            )

            if (inserted) {
                logger.info(
                    "Enqueued notification for email UID {} from mailbox {}",
                    message.uid,
                    account.username,
                )
            } else {
                logger.debug(
                    "Notification for email UID {} from mailbox {} " + "already exists in outbox",
                    message.uid,
                    account.username,
                )
            }
        }
    }

    private suspend fun <T> withAccountProcessingLock(
        account: MailAccountConfig,
        block: suspend () -> T,
    ): T {
        val accountKey = buildAccountKey(account)
        val mutex = accountProcessingLocks.computeIfAbsent(accountKey) {
            Mutex()
        }

        return mutex.withLock {
            block()
        }
    }

    private suspend fun initializeAccount(
        account: MailAccountConfig,
        accountKey: String,
        idleInbox: IMAPFolder?,
    ) {
        val cursor = if (idleInbox != null) {
            imapClient.fetchCursor(idleInbox)
        } else {
            imapClient.fetchCursor(account)
        }

        stateRepository.save(
            accountKey = accountKey,
            uidValidity = cursor.uidValidity,
            lastUid = cursor.latestUid,
        )

        scope.launch {
            runCatching {
                telegramControlClient.sendMessage(
                    chatId = telegramChatId,
                    text = buildString {
                        appendLine("✅ Почтовый ящик подключён")
                        appendLine()
                        appendLine("Название: ${account.name}")
                        appendLine("Адрес: ${account.username}")
                        append("Старые письма пересылаться не будут.")
                    },
                )
            }
        }

        logger.info(
            "Initialized mailbox {} at UID {}",
            account.username,
            cursor.latestUid,
        )
    }

    private fun summaryButtons(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): List<TelegramInlineButton> {
        return listOf(
            TelegramInlineButton(
                text = "Текст",
                callbackData = MailViewCallbackCodec.encode(
                    action = MailViewAction.TEXT,
                    account = account,
                    uidValidity = uidValidity,
                    uid = uid,
                ),
            ),
            TelegramInlineButton(
                text = "Вложения",
                callbackData = MailViewCallbackCodec.encode(
                    action = MailViewAction.ATTACHMENTS,
                    account = account,
                    uidValidity = uidValidity,
                    uid = uid,
                ),
            ),
        )
    }

    private fun textViewButtons(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): List<TelegramInlineButton> {
        return listOf(
            TelegramInlineButton(
                text = "назад",
                callbackData = MailViewCallbackCodec.encode(
                    action = MailViewAction.BACK,
                    account = account,
                    uidValidity = uidValidity,
                    uid = uid,
                ),
            ),
            TelegramInlineButton(
                text = "Вложения",
                callbackData = MailViewCallbackCodec.encode(
                    action = MailViewAction.ATTACHMENTS,
                    account = account,
                    uidValidity = uidValidity,
                    uid = uid,
                ),
            ),
        )
    }

    private fun formatNotification(
        account: MailAccountConfig,
        message: EmailSummary,
        attachmentsKnown: Boolean,
    ): String {
        return buildString {
            appendLine("📨 Новое письмо")
            appendLine()
            appendLine("Кому: ${account.name} (${account.username})")
            appendLine("От: ${message.from}")
            appendLine("Тема: ${message.subject}")
            appendAttachmentOverview(
                message = message,
                attachmentsKnown = attachmentsKnown,
            )
        }.trimEnd()
    }

    private fun formatFullMessage(
        account: MailAccountConfig,
        message: EmailSummary,
    ): String {
        val sentAt = message.sentAt
            ?.let(dateFormatter::format)
            ?: "(дата неизвестна)"
        val body = message.body
            ?.takeIf { text -> text.isNotBlank() }
            ?: "(текст письма отсутствует или не удалось распознать)"

        return buildString {
            appendLine("📄 Содержимое письма")
            appendLine()
            appendLine("Ящик: ${account.name}")
            appendLine("Адрес: ${account.username}")
            appendLine("От: ${message.from}")
            appendLine("Тема: ${message.subject}")
            appendLine("Дата: $sentAt")
            appendLine()
            appendLine("Текст:")
            appendLine(body)
            appendLine()
            appendAttachmentOverview(
                message = message,
                attachmentsKnown = true,
            )
        }.trimEnd()
    }

    private fun StringBuilder.appendAttachmentOverview(
        message: EmailSummary,
        attachmentsKnown: Boolean,
    ) {
        if (!attachmentsKnown) {
            append("Вложения: не удалось определить")
            return
        }

        val attachmentNames = buildList {
            message.attachments.forEach { attachment ->
                add(attachment.fileName)
            }
            message.skippedAttachments.forEach { attachment ->
                add(attachment.fileName)
            }
        }

        if (attachmentNames.isEmpty()) {
            append("Вложения: нет")
            return
        }

        appendLine("Вложения:")
        attachmentNames.forEachIndexed { index, fileName ->
            append(index + 1)
            append(". ")
            append(fileName)
            append(" (")
            append(fileExtensionLabel(fileName))
            append(')')

            if (index < attachmentNames.lastIndex) {
                appendLine()
            }
        }
    }

    private fun fileExtensionLabel(
        fileName: String,
    ): String {
        val extension = fileName
            .substringAfterLast('.', missingDelimiterValue = "")
            .trim()
            .takeIf { value ->
                value.isNotBlank() &&
                        value.length <= 15 && value.none(Char::isWhitespace)
            }

        return extension
            ?.let { value -> ".${value.lowercase()}" }
            ?: "без расширения"
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

            message.skippedAttachments.forEachIndexed { index, attachment ->
                append(index + 1)
                append(". ")
                append(attachment.fileName)
                append(": ")
                append(attachment.reason)

                if (index < message.skippedAttachments.lastIndex) {
                    appendLine()
                }
            }
        }
    }

    private suspend fun deleteMessageSafely(
        messageId: Long,
    ) {
        try {
            telegramControlClient.deleteMessage(
                chatId = telegramChatId,
                messageId = messageId,
            )
        } catch (exception: TelegramApiException) {
            val harmless = exception.description.contains(
                other = "message to delete not found",
                ignoreCase = true,
            )

            if (!harmless) {
                logger.warn(
                    "Failed to delete Telegram message {}: TelegramApiException",
                    messageId,
                )
            }
        } catch (exception: Exception) {
            logger.warn(
                "Failed to delete Telegram message {}: {}",
                messageId,
                exception.javaClass.simpleName,
            )
        }
    }

    private fun logDeliveryTiming(
        account: MailAccountConfig,
        message: EmailSummary,
    ) {
        val detectedAt = Instant.now()
        val transportSeconds = if (
            message.sentAt != null &&
            message.receivedAt != null
        ) {
            Duration.between(
                message.sentAt,
                message.receivedAt,
            )
                .seconds
                .coerceAtLeast(0)
        } else {
            null
        }

        val detectionSeconds = message.receivedAt
            ?.let { receivedAt ->
                Duration.between(receivedAt, detectedAt)
                    .seconds
                    .coerceAtLeast(0)
            }

        logger.info(
            "Email UID {} from mailbox {}: mail transport delay={} s, " + "bot detection delay={} s",
            message.uid,
            account.username,
            transportSeconds?.toString() ?: "unknown",
            detectionSeconds?.toString() ?: "unknown",
        )
    }

    private fun interactiveEmailKey(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): String {
        return "${MailViewCallbackCodec.accountCode(account)}:$uidValidity:$uid"
    }

    private fun buildAccountKey(
        account: MailAccountConfig,
    ): String {
        return "${account.host}:${account.username}".lowercase()
    }

    override fun close() {
        scope.cancel()
    }
}
