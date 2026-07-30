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
import io.github.lightsmit.storage.MailOutboxEmailMetadata

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

    private val summaryMessageIds =
        ConcurrentHashMap<String, Long>()

    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd.MM.yyyy, HH:mm")
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
                "Configured mail account no longer exists: " +
                        item.accountKey,
            )

        val actualAccountCode =
            MailViewCallbackCodec.accountCode(account)

        if (actualAccountCode != item.accountCode) {
            throw PermanentMailDeliveryException(
                "Mail account code does not match the current configuration",
            )
        }

        val emailKey = interactiveEmailKey(
            account = account,
            uidValidity = item.uidValidity,
            uid = item.uid,
        )

        val metadata = item.emailMetadata

        val telegramMessageId = if (metadata == null) {
            deliverLegacyNotification(
                account = account,
                item = item,
            )
        } else {
            telegramControlClient.sendMessageWithButtons(
                chatId = telegramChatId,
                text = formatPendingNotification(
                    account = account,
                    metadata = metadata,
                ),
                buttons = pendingSummaryButtons(
                    account = account,
                    uidValidity = item.uidValidity,
                    uid = item.uid,
                ),
            )
        }

        summaryMessageIds[emailKey] = telegramMessageId

        if (metadata != null) {
            scope.launch {
                enrichSummaryCard(
                    account = account,
                    uidValidity = item.uidValidity,
                    uid = item.uid,
                    emailKey = emailKey,
                    telegramMessageId = telegramMessageId,
                )
            }
        }

        logger.info(
            "Delivered outbox item {} for email UID {} from mailbox {}",
            item.id,
            item.uid,
            account.username,
        )

        return telegramMessageId
    }

    private suspend fun deliverLegacyNotification(
        account: MailAccountConfig,
        item: MailOutboxItem,
    ): Long {
        val message = contentLoader.get(
            account = account,
            uidValidity = item.uidValidity,
            uid = item.uid,
        ) ?: throw PermanentMailDeliveryException(
            "Email UID ${item.uid} is no longer available " +
                    "in mailbox ${account.username}",
        )

        return telegramControlClient.sendMessageWithButtons(
            chatId = telegramChatId,
            text = formatNotification(
                account = account,
                message = message,
            ),
            buttons = summaryButtons(
                account = account,
                message = message,
                uidValidity = item.uidValidity,
                uid = item.uid,
            ),
        )
    }

    private suspend fun enrichSummaryCard(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
        emailKey: String,
        telegramMessageId: Long,
    ) {
        val message = try {
            contentLoader.get(
                account = account,
                uidValidity = uidValidity,
                uid = uid,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn(
                "Failed to load full content for email UID {} from mailbox {}: {}",
                uid,
                account.username,
                exception.javaClass.simpleName,
            )
            return
        }

        if (message == null) {
            logger.warn(
                "Email UID {} from mailbox {} is no longer available for card enrichment",
                uid,
                account.username,
            )
            return
        }

        navigationMutex.withLock {
            if (summaryMessageIds[emailKey] != telegramMessageId) {
                return@withLock
            }

            try {
                telegramControlClient.editMessageWithButtons(
                    chatId = telegramChatId,
                    messageId = telegramMessageId,
                    text = formatNotification(
                        account = account,
                        message = message,
                    ),
                    buttons = summaryButtons(
                        account = account,
                        message = message,
                        uidValidity = uidValidity,
                        uid = uid,
                    ),
                )

                logger.info(
                    "Enriched Telegram card for email UID {} from mailbox {}",
                    uid,
                    account.username,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: TelegramApiException) {
                val harmless =
                    exception.description.contains(
                        other = "message is not modified",
                        ignoreCase = true,
                    ) ||
                            exception.description.contains(
                                other = "message to edit not found",
                                ignoreCase = true,
                            )

                if (!harmless) {
                    logger.warn(
                        "Failed to enrich Telegram card for email UID {}: {}",
                        uid,
                        exception.description,
                    )
                }
            } catch (exception: Exception) {
                logger.warn(
                    "Failed to enrich Telegram card for email UID {}: {}",
                    uid,
                    exception.javaClass.simpleName,
                )
            }
        }
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
        val emailKey = interactiveEmailKey(
            account = account,
            uidValidity = expectedUidValidity,
            uid = uid,
        )

        summaryMessageIds.remove(
            emailKey,
            sourceMessageId,
        )

        val message = loadEmailOrNotify(
            account = account,
            expectedUidValidity = expectedUidValidity,
            uid = uid,
        ) ?: return

        val fullText = formatFullMessage(
            account = account,
            message = message,
        )

        val buttons = textViewButtons(
            account = account,
            message = message,
            uidValidity = expectedUidValidity,
            uid = uid,
        )

        navigationMutex.withLock {
            if (
                telegramControlClient.fitsSingleTextMessage(
                    fullText,
                )
            ) {
                telegramControlClient.editMessageWithButtons(
                    chatId = telegramChatId,
                    messageId = sourceMessageId,
                    text = fullText,
                    buttons = buttons,
                )

                textViewMessageIds.remove(emailKey)
            } else {
                val newMessageIds =
                    telegramControlClient.sendLongMessageWithButtons(
                        chatId = telegramChatId,
                        text = fullText,
                        buttons = buttons,
                    )

                textViewMessageIds[emailKey] = newMessageIds
                deleteMessageSafely(sourceMessageId)
            }
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

        val summaryText = formatNotification(
            account = account,
            message = message,
        )

        val buttons = summaryButtons(
            account = account,
            message = message,
            uidValidity = expectedUidValidity,
            uid = uid,
        )

        val emailKey = interactiveEmailKey(
            account = account,
            uidValidity = expectedUidValidity,
            uid = uid,
        )

        navigationMutex.withLock {
            val longViewMessageIds =
                textViewMessageIds.remove(emailKey)

            if (longViewMessageIds == null) {
                telegramControlClient.editMessageWithButtons(
                    chatId = telegramChatId,
                    messageId = sourceMessageId,
                    text = summaryText,
                    buttons = buttons,
                )
                summaryMessageIds[emailKey] = sourceMessageId
            } else {
                val summaryMessageId =
                    telegramControlClient.sendMessageWithButtons(
                        chatId = telegramChatId,
                        text = summaryText,
                        buttons = buttons,
                    )

                buildSet {
                    add(sourceMessageId)
                    addAll(longViewMessageIds)
                }.forEach { messageId ->
                    deleteMessageSafely(messageId)
                }
                summaryMessageIds[emailKey] = summaryMessageId
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

        val attachmentTotal = message.attachments.size

        val statusMessageId = if (attachmentTotal > 0) {
            try {
                telegramControlClient.sendMessage(
                    chatId = telegramChatId,
                    text = "⏳ Отправляю вложения: 0/$attachmentTotal",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.debug(
                    "Failed to display attachment progress for email UID {}",
                    uid,
                )
                null
            }
        } else {
            null
        }

        try {
            message.attachments.forEachIndexed { index, attachment ->
                val startedAt = System.currentTimeMillis()

                telegramMediaClient.sendAttachment(
                    chatId = telegramChatId,
                    attachment = attachment,
                )

                statusMessageId?.let { messageId ->
                    editMessageSafely(
                        messageId = messageId,
                        text = "⏳ Отправляю вложения: " +
                                "${index + 1}/$attachmentTotal",
                    )
                }

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

            statusMessageId?.let { messageId ->
                editMessageSafely(
                    messageId = messageId,
                    text = "✅ Вложения отправлены: $attachmentTotal",
                )

                delay(1_500)
                deleteMessageSafely(messageId)
            }

            logger.info(
                "Displayed attachments for email UID {} from mailbox {}: {} file(s)",
                uid,
                account.username,
                message.attachments.size,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            statusMessageId?.let { messageId ->
                editMessageSafely(
                    messageId = messageId,
                    text = "⚠️ Не удалось отправить вложения. " +
                            "Повторите попытку.",
                )
            }

            throw exception
        }
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
                emailMetadata = MailOutboxEmailMetadata(
                    from = message.from,
                    subject = message.subject,
                    sentAt = message.sentAt,
                    receivedAt = message.receivedAt,
                ),
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

    private fun pendingSummaryButtons(
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
    ): List<TelegramInlineButton> {
        return listOf(
            TelegramInlineButton(
                text = "📄 Текст",
                callbackData = MailViewCallbackCodec.encode(
                    action = MailViewAction.TEXT,
                    account = account,
                    uidValidity = uidValidity,
                    uid = uid,
                ),
            ),
            TelegramInlineButton(
                text = "📎 Вложения",
                callbackData = MailViewCallbackCodec.encode(
                    action = MailViewAction.ATTACHMENTS,
                    account = account,
                    uidValidity = uidValidity,
                    uid = uid,
                ),
            ),
        )
    }

    private fun summaryButtons(
        account: MailAccountConfig,
        message: EmailSummary,
        uidValidity: Long,
        uid: Long,
    ): List<TelegramInlineButton> {
        return buildList {
            add(
                TelegramInlineButton(
                    text = "📄 Текст",
                    callbackData = MailViewCallbackCodec.encode(
                        action = MailViewAction.TEXT,
                        account = account,
                        uidValidity = uidValidity,
                        uid = uid,
                    ),
                ),
            )

            val attachmentCount = attachmentCount(message)

            if (attachmentCount > 0) {
                add(
                    TelegramInlineButton(
                        text = "📎 Вложения · $attachmentCount",
                        callbackData = MailViewCallbackCodec.encode(
                            action = MailViewAction.ATTACHMENTS,
                            account = account,
                            uidValidity = uidValidity,
                            uid = uid,
                        ),
                    ),
                )
            }
        }
    }

    private fun textViewButtons(
        account: MailAccountConfig,
        message: EmailSummary,
        uidValidity: Long,
        uid: Long,
    ): List<TelegramInlineButton> {
        return buildList {
            add(
                TelegramInlineButton(
                    text = "← Назад",
                    callbackData = MailViewCallbackCodec.encode(
                        action = MailViewAction.BACK,
                        account = account,
                        uidValidity = uidValidity,
                        uid = uid,
                    ),
                ),
            )

            val attachmentCount = attachmentCount(message)

            if (attachmentCount > 0) {
                add(
                    TelegramInlineButton(
                        text = "📎 Вложения · $attachmentCount",
                        callbackData = MailViewCallbackCodec.encode(
                            action = MailViewAction.ATTACHMENTS,
                            account = account,
                            uidValidity = uidValidity,
                            uid = uid,
                        ),
                    ),
                )
            }
        }
    }

    private fun formatPendingNotification(
        account: MailAccountConfig,
        metadata: MailOutboxEmailMetadata,
    ): String {
        val messageDate = formatMessageDate(
            sentAt = metadata.sentAt,
            receivedAt = metadata.receivedAt,
        )

        return buildString {
            appendLine("📨 Новое письмо")
            appendLine()
            appendLine("📬 Ящик: ${account.name}")
            appendLine("📧 Кому: ${account.username}")
            appendLine("👤 От: ${metadata.from}")
            appendLine("📝 Тема: ${metadata.subject}")
            appendLine("🕒 Дата: $messageDate")
            append("📎 Вложения: проверяются…")
        }
    }

    private fun formatNotification(
        account: MailAccountConfig,
        message: EmailSummary,
    ): String {
        val messageDate = formatMessageDate(message)
        val attachmentSummary =
            formatAttachmentCount(attachmentCount(message))

        return buildString {
            appendLine("📨 Новое письмо")
            appendLine()
            appendLine("📬 Ящик: ${account.name}")
            appendLine("📧 Кому: ${account.username}")
            appendLine("👤 От: ${message.from}")
            appendLine("📝 Тема: ${message.subject}")
            appendLine("🕒 Дата: $messageDate")
            append("📎 Вложения: $attachmentSummary")
        }
    }

    private fun formatFullMessage(
        account: MailAccountConfig,
        message: EmailSummary,
    ): String {
        val messageDate = formatMessageDate(message)
        val body = message.body
            ?.takeIf { text -> text.isNotBlank() }
            ?: "(текст письма отсутствует или не удалось распознать)"

        return buildString {
            appendLine("📄 Письмо")
            appendLine()
            appendLine("📬 Ящик: ${account.name}")
            appendLine("📧 Кому: ${account.username}")
            appendLine("👤 От: ${message.from}")
            appendLine("📝 Тема: ${message.subject}")
            appendLine("🕒 Дата: $messageDate")
            appendLine()
            appendLine("──────────")
            appendLine(body)
            appendLine("──────────")
            appendLine()
            appendAttachmentOverview(message)
        }.trimEnd()
    }

    private fun StringBuilder.appendAttachmentOverview(
        message: EmailSummary,
    ) {
        val attachmentNames = buildList {
            message.attachments.forEach { attachment ->
                add(attachment.fileName)
            }

            message.skippedAttachments.forEach { attachment ->
                add(attachment.fileName)
            }
        }

        if (attachmentNames.isEmpty()) {
            append("📎 Вложения: нет")
            return
        }

        appendLine(
            "📎 Вложения: ${formatAttachmentCount(attachmentNames.size)}",
        )

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

    private fun attachmentCount(
        message: EmailSummary,
    ): Int {
        return message.attachments.size +
                message.skippedAttachments.size
    }

    private fun formatMessageDate(
        message: EmailSummary,
    ): String {
        return formatMessageDate(
            sentAt = message.sentAt,
            receivedAt = message.receivedAt,
        )
    }

    private fun formatMessageDate(
        sentAt: Instant?,
        receivedAt: Instant?,
    ): String {
        return (sentAt ?: receivedAt)
            ?.let(dateFormatter::format)
            ?: "неизвестна"
    }

    private fun formatAttachmentCount(
        count: Int,
    ): String {
        if (count == 0) {
            return "нет"
        }

        val lastTwoDigits = count % 100
        val lastDigit = count % 10

        val noun = when {
            lastTwoDigits in 11..14 -> "файлов"
            lastDigit == 1 -> "файл"
            lastDigit in 2..4 -> "файла"
            else -> "файлов"
        }

        return "$count $noun"
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

    private suspend fun editMessageSafely(
        messageId: Long,
        text: String,
    ) {
        try {
            telegramControlClient.editMessage(
                chatId = telegramChatId,
                messageId = messageId,
                text = text,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug(
                "Failed to edit Telegram status message {}: {}",
                messageId,
                exception.javaClass.simpleName,
            )
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