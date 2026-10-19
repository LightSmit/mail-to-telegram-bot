package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.mail.EmailSummary
import io.github.lightsmit.mail.ImapMailClient
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxEmailMetadata
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
import kotlinx.coroutines.isActive
import org.eclipse.angus.mail.imap.IMAPFolder
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private data class ActiveView(
    val messageIds: List<Long>,
    val openedAt: Instant = Instant.now()
)

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
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    private val navigationMutex = Mutex()
    private val accountProcessingLocks =
        ConcurrentHashMap<String, Mutex>()

    private val textViewMessageIds = ConcurrentHashMap<String, ActiveView>()

    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd.MM.yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())

    init {
        require(accounts.isNotEmpty()) {
            "At least one mail account is required"
        }
        require(accountsByKey.size == accounts.size) {
            "Mail account host and username combinations must be unique"
        }

        // Фоновая очистка "забытых" сообщений (старше 24 часов)
        scope.launch {
            while (isActive) {
                delay(Duration.ofHours(1).toMillis())
                val now = Instant.now()
                textViewMessageIds.entries.removeIf { (_, view) ->
                    Duration.between(view.openedAt, now).toHours() >= 24
                }
            }
        }
    }



    suspend fun deliverOutboxNotification(
        item: MailOutboxItem,
    ): Long {
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

        val telegramMessageId = when (item.operation) {
            MailOutboxOperation.SEND_NOTIFICATION -> {
                deliverInitialNotification(
                    account = account,
                    item = item,
                )
            }

            MailOutboxOperation.ENRICH_NOTIFICATION -> {
                enrichSummaryCard(
                    account = account,
                    item = item,
                )
            }
        }

        logger.info(
            "Delivered outbox operation {} for email UID {} from mailbox {}",
            item.operation,
            item.uid,
            account.username,
        )

        return telegramMessageId
    }

    private suspend fun deliverInitialNotification(
        account: MailAccountConfig,
        item: MailOutboxItem,
    ): Long {
        val metadata = item.emailMetadata

        if (metadata == null) {
            return deliverLegacyNotification(
                account = account,
                item = item,
            )
        }

        return telegramControlClient.sendMessageWithButtons(
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

        return navigationMutex.withLock {
            val notification = outboxRepository.find(
                accountKey = item.accountKey,
                uidValidity = item.uidValidity,
                uid = item.uid,
                operation =
                    MailOutboxOperation.SEND_NOTIFICATION,
            ) ?: throw PermanentMailDeliveryException(
                "Notification state for email UID ${item.uid} no longer exists",
            )

            val telegramMessageId =
                notification.telegramMessageId
                    ?: throw PermanentMailDeliveryException(
                        "Telegram message ID for email UID ${item.uid} is missing",
                    )

            if (!notification.summaryActive) {
                logger.info(
                    "Skipped Telegram card enrichment for email UID {} " +
                            "because summary view is inactive",
                    item.uid,
                )

                return@withLock telegramMessageId
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
                        uidValidity = item.uidValidity,
                        uid = item.uid,
                    ),
                )
            } catch (exception: TelegramApiException) {
                val notModified =
                    exception.description.contains(
                        other = "message is not modified",
                        ignoreCase = true,
                    )

                if (!notModified) {
                    throw exception
                }
            }

            logger.info(
                "Enriched Telegram card for email UID {} from mailbox {}",
                item.uid,
                account.username,
            )

            telegramMessageId
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

        val accountKey = buildAccountKey(account)

        navigationMutex.withLock {
            val stateUpdated =
                outboxRepository.markSummaryInactive(
                    accountKey = accountKey,
                    uidValidity = expectedUidValidity,
                    uid = uid,
                )

            if (!stateUpdated) {
                logger.warn(
                    "Failed to mark summary inactive for email UID {}",
                    uid,
                )
            }

            try {
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

                    textViewMessageIds[emailKey] = ActiveView(newMessageIds)
                    deleteMessageSafely(sourceMessageId)
                }
            } catch (exception: Exception) {
                outboxRepository.markSummaryReady(
                    accountKey = accountKey,
                    uidValidity = expectedUidValidity,
                    uid = uid,
                    telegramMessageId = sourceMessageId,
                )

                throw exception
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
        val accountKey = buildAccountKey(account)

        navigationMutex.withLock {
            val longViewMessageIds = textViewMessageIds.remove(emailKey)?.messageIds

            if (longViewMessageIds == null) {
                telegramControlClient.editMessageWithButtons(
                    chatId = telegramChatId,
                    messageId = sourceMessageId,
                    text = summaryText,
                    buttons = buttons,
                )

                val stateUpdated =
                    outboxRepository.markSummaryReady(
                        accountKey = accountKey,
                        uidValidity = expectedUidValidity,
                        uid = uid,
                        telegramMessageId = sourceMessageId,
                    )

                if (!stateUpdated) {
                    logger.warn(
                        "Failed to persist summary view for email UID {}",
                        uid,
                    )
                }
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

                val stateUpdated =
                    outboxRepository.markSummaryReady(
                        accountKey = accountKey,
                        uidValidity = expectedUidValidity,
                        uid = uid,
                        telegramMessageId = summaryMessageId,
                    )

                if (!stateUpdated) {
                    logger.warn(
                        "Failed to persist summary view for email UID {}",
                        uid,
                    )
                }
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