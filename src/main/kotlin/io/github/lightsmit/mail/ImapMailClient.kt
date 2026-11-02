package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.search.AndTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import java.nio.file.Path

class ImapMailClient(
    private val attachmentTempDirectory: Path,
    private val maxAttachmentSizeBytes: Long,
) {

    suspend fun fetchCursor(
        account: MailAccountConfig,
    ): MailboxCursor = withContext(Dispatchers.IO) {
        withInbox(account, METADATA_TIMEOUT_MILLIS) { inbox, uidFolder ->
            createCursor(inbox, uidFolder)
        }
    }

    suspend fun fetchCursor(
        inbox: IMAPFolder,
    ): MailboxCursor = withContext(Dispatchers.IO) {
        createCursor(inbox, inbox)
    }

    suspend fun fetchAfterUid(
        account: MailAccountConfig,
        afterUid: Long,
        limit: Int,
    ): MailboxBatch = withContext(Dispatchers.IO) {
        withInbox(account, METADATA_TIMEOUT_MILLIS) { inbox, uidFolder ->
            fetchAfterUidFromFolder(
                inbox = inbox,
                uidFolder = uidFolder,
                afterUid = afterUid,
                limit = limit,
            )
        }
    }

    suspend fun fetchAfterUid(
        account: MailAccountConfig,
        inbox: IMAPFolder,
        afterUid: Long,
        limit: Int,
    ): MailboxBatch = withContext(Dispatchers.IO) {
        fetchAfterUidFromFolder(
            inbox = inbox,
            uidFolder = inbox,
            afterUid = afterUid,
            limit = limit,
        )
    }

    suspend fun fetchByUid(
        account: MailAccountConfig,
        expectedUidValidity: Long,
        uid: Long,
    ): EmailSummary? = withContext(Dispatchers.IO) {
        require(expectedUidValidity > 0) {
            "UIDVALIDITY must be greater than zero"
        }
        require(uid > 0) {
            "UID must be greater than zero"
        }

        withInbox(account, CONTENT_TIMEOUT_MILLIS) { inbox, uidFolder ->
            if (uidFolder.uidValidity != expectedUidValidity) {
                return@withInbox null
            }

            val message = uidFolder.getMessageByUID(uid)
                ?: return@withInbox null

            val fetchProfile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.CONTENT_INFO)
                add(UIDFolder.FetchProfileItem.UID)
            }

            inbox.fetch(arrayOf<Message>(message), fetchProfile)
            createFullSummary(message, uid)
        }
    }

    private fun createCursor(
        inbox: Folder,
        uidFolder: UIDFolder,
    ): MailboxCursor {
        return MailboxCursor(
            uidValidity = uidFolder.uidValidity,
            latestUid = findLatestUid(inbox, uidFolder),
        )
    }

    private fun fetchAfterUidFromFolder(
        inbox: Folder,
        uidFolder: UIDFolder,
        afterUid: Long,
        limit: Int,
    ): MailboxBatch {
        require(afterUid >= 0) { "UID must not be negative" }
        require(limit > 0) { "Message limit must be greater than zero" }

        val uidValidity = uidFolder.uidValidity
        val latestUid = findLatestUid(inbox, uidFolder)

        if (latestUid == 0L || latestUid <= afterUid) {
            return MailboxBatch(
                uidValidity = uidValidity,
                latestUid = latestUid,
                messages = emptyList(),
            )
        }

        val messages = uidFolder.getMessagesByUID(
            afterUid + 1,
            UIDFolder.LASTUID,
        )

        if (messages.isEmpty()) {
            return MailboxBatch(
                uidValidity = uidValidity,
                latestUid = latestUid,
                messages = emptyList(),
            )
        }

        val fetchProfile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(UIDFolder.FetchProfileItem.UID)
            // Заголовки, по которым узнаем рассылки (для фильтра "не присылать рассылки")
            BULK_HEADERS.forEach { header -> add(header) }
        }
        inbox.fetch(messages, fetchProfile)

        val summaries = messages
            .sortedBy { message -> uidFolder.getUID(message) }
            .take(limit)
            .map { message ->
                createMetadataSummary(
                    message = message,
                    uid = uidFolder.getUID(message),
                )
            }

        return MailboxBatch(
            uidValidity = uidValidity,
            latestUid = latestUid,
            messages = summaries,
        )
    }

    private fun createMetadataSummary(
        message: Message,
        uid: Long,
    ): EmailSummary {
        return EmailSummary(
            uid = uid,
            from = formatSender(message),
            subject = formatSubject(message),
            sentAt = message.sentDate?.toInstant(),
            receivedAt = message.receivedDate?.toInstant(),
            body = null,
            attachments = emptyList(),
            skippedAttachments = emptyList(),
            senderAddress = senderAddress(message),
            isBulk = isBulk(message),
        )
    }

    private fun senderAddress(message: Message): String? {
        return runCatching {
            (message.from?.firstOrNull() as? InternetAddress)?.address?.trim()?.lowercase()
        }.getOrNull()
    }

    private fun isBulk(message: Message): Boolean {
        return runCatching {
            fun header(name: String): String? = message.getHeader(name)?.firstOrNull()?.trim()?.lowercase()

            header("List-Unsubscribe") != null ||
                header("List-Id") != null ||
                header("Precedence") in setOf("bulk", "list", "junk") ||
                header("Auto-Submitted").let { value -> value != null && value != "no" }
        }.getOrDefault(false)
    }

    private fun createFullSummary(
        message: Message,
        uid: Long,
    ): EmailSummary {
        val content = EmailContentExtractor.extract(
            part = message,
            tempDirectory = attachmentTempDirectory,
            maxAttachmentSizeBytes = maxAttachmentSizeBytes,
            includeAttachments = true,
        )

        return EmailSummary(
            uid = uid,
            from = formatSender(message),
            subject = formatSubject(message),
            sentAt = message.sentDate?.toInstant(),
            receivedAt = message.receivedDate?.toInstant(),
            body = content.body,
            attachments = content.attachments,
            skippedAttachments = content.skippedAttachments,
        )
    }

    private fun formatSender(message: Message): String {
        return message.from
            ?.joinToString(", ") { address ->
                (address as? InternetAddress)
                    ?.toUnicodeString()
                    ?: address.toString()
            }
            ?: "(sender unknown)"
    }

    private fun formatSubject(message: Message): String {
        return message.subject
            ?.takeIf { it.isNotBlank() }
            ?: "(no subject)"
    }

    private fun findLatestUid(
        inbox: Folder,
        uidFolder: UIDFolder,
    ): Long {
        if (inbox.messageCount == 0) return 0
        return uidFolder.getUID(inbox.getMessage(inbox.messageCount))
    }

    /**
     * Отмечает письмо прочитанным в самом ящике (флаг \Seen).
     * false - письмо не найдено (удалено, перемещено или сменилась нумерация).
     */
    suspend fun markSeen(
        account: MailAccountConfig,
        expectedUidValidity: Long,
        uid: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        withInbox(account, METADATA_TIMEOUT_MILLIS, Folder.READ_WRITE) { _, uidFolder ->
            if (uidFolder.uidValidity != expectedUidValidity) {
                return@withInbox false
            }

            val message = uidFolder.getMessageByUID(uid)
                ?: return@withInbox false

            message.setFlag(Flags.Flag.SEEN, true)
            true
        }
    }

    /** Данные для ответа на письмо: кому, тема и заголовки цепочки. null - письмо не найдено. */
    suspend fun fetchReplyContext(
        account: MailAccountConfig,
        expectedUidValidity: Long,
        uid: Long,
    ): ReplyContext? = withContext(Dispatchers.IO) {
        withInbox(account, METADATA_TIMEOUT_MILLIS) { _, uidFolder ->
            if (uidFolder.uidValidity != expectedUidValidity) {
                return@withInbox null
            }

            val message = uidFolder.getMessageByUID(uid)
                ?: return@withInbox null

            // getReplyTo возвращает From, если заголовка Reply-To нет
            val recipients = runCatching { message.replyTo?.toList() }.getOrNull()
                .orEmpty()
                .filterIsInstance<InternetAddress>()
                .filter { address -> !address.address.isNullOrBlank() }

            fun header(name: String): String? =
                runCatching { message.getHeader(name)?.firstOrNull() }.getOrNull()
                    ?.replace(WHITESPACE, " ")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)

            ReplyContext(
                recipients = recipients,
                subject = runCatching { message.subject }.getOrNull().orEmpty(),
                messageId = header("Message-ID"),
                references = header("References"),
            )
        }
    }

    /**
     * Страница писем для просмотра в приложении, новые сверху.
     * search - поиск по теме, отправителю и получателю (на стороне почтового сервера).
     */
    suspend fun listMessages(
        account: MailAccountConfig,
        role: MailFolderRole,
        offset: Int,
        limit: Int,
        search: String?,
        unreadOnly: Boolean,
    ): MailListPage = withContext(Dispatchers.IO) {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit in 1..100) { "Limit must be between 1 and 100" }

        withFolder(account, CONTENT_TIMEOUT_MILLIS, Folder.READ_ONLY, role) { folder, uidFolder ->
            val terms = buildList<SearchTerm> {
                search?.trim()?.takeIf(String::isNotEmpty)?.let { query ->
                    add(
                        OrTerm(
                            arrayOf(
                                SubjectTerm(query),
                                FromStringTerm(query),
                                RecipientStringTerm(Message.RecipientType.TO, query),
                            ),
                        ),
                    )
                }
                if (unreadOnly) add(FlagTerm(Flags(Flags.Flag.SEEN), false))
            }

            // Без условий берем по номерам с конца папки, с условиями - через SEARCH на сервере
            val found: Array<Message>? = when (terms.size) {
                0 -> null
                1 -> folder.search(terms.single())
                else -> folder.search(AndTerm(terms.toTypedArray()))
            }

            val total = found?.size ?: folder.messageCount
            val end = total - offset
            val start = maxOf(0, end - limit)

            val messages: Array<Message> = when {
                end <= 0 -> emptyArray()
                found != null -> found.sortedBy { message -> message.messageNumber }.subList(start, end).toTypedArray()
                else -> folder.getMessages(start + 1, end)
            }

            if (messages.isNotEmpty()) {
                val profile = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(FetchProfile.Item.CONTENT_INFO)
                    add(UIDFolder.FetchProfileItem.UID)
                }
                folder.fetch(messages, profile)
            }

            MailListPage(
                uidValidity = uidFolder.uidValidity,
                total = total,
                items = messages.reversed().map { message -> listItem(message, uidFolder.getUID(message)) },
                hasMore = start > 0,
            )
        }
    }

    /** Письмо целиком для просмотра. Флаг "прочитано" не ставится (ящик открыт только на чтение). */
    suspend fun readMessage(
        account: MailAccountConfig,
        role: MailFolderRole,
        expectedUidValidity: Long,
        uid: Long,
    ): MailMessageView? = withContext(Dispatchers.IO) {
        withFolder(account, CONTENT_TIMEOUT_MILLIS, Folder.READ_ONLY, role) { _, uidFolder ->
            if (uidFolder.uidValidity != expectedUidValidity) {
                return@withFolder null
            }

            val message = uidFolder.getMessageByUID(uid)
                ?: return@withFolder null

            val content = EmailContentExtractor.extract(
                part = message,
                tempDirectory = attachmentTempDirectory,
                maxAttachmentSizeBytes = maxAttachmentSizeBytes,
                includeAttachments = false,
            )

            MailMessageView(
                uid = uid,
                from = formatSender(message),
                to = formatRecipients(message, Message.RecipientType.TO),
                cc = formatRecipients(message, Message.RecipientType.CC),
                subject = formatSubject(message),
                date = (message.receivedDate ?: message.sentDate)?.toInstant(),
                body = content.body?.take(MAX_VIEW_BODY_CHARS),
                attachments = EmailContentExtractor.attachmentNames(message),
                seen = runCatching { message.isSet(Flags.Flag.SEEN) }.getOrDefault(true),
            )
        }
    }

    private fun listItem(message: Message, uid: Long): MailListItem {
        val sender = runCatching { message.from?.firstOrNull() as? InternetAddress }.getOrNull()
        return MailListItem(
            uid = uid,
            fromName = sender?.personal?.takeIf(String::isNotBlank)
                ?: sender?.address
                ?: "(отправитель неизвестен)",
            fromAddress = sender?.address?.lowercase(),
            to = formatRecipients(message, Message.RecipientType.TO),
            subject = runCatching { message.subject }.getOrNull()?.takeIf(String::isNotBlank) ?: "(без темы)",
            date = runCatching { (message.receivedDate ?: message.sentDate)?.toInstant() }.getOrNull(),
            seen = runCatching { message.isSet(Flags.Flag.SEEN) }.getOrDefault(true),
            hasAttachments = runCatching { message.isMimeType("multipart/mixed") }.getOrDefault(false),
        )
    }

    private fun formatRecipients(message: Message, type: Message.RecipientType): String {
        return runCatching { message.getRecipients(type) }.getOrNull()
            ?.joinToString(", ") { address ->
                (address as? InternetAddress)?.toUnicodeString() ?: address.toString()
            }
            .orEmpty()
    }

    private fun <T> withInbox(
        account: MailAccountConfig,
        readTimeoutMillis: Int,
        mode: Int = Folder.READ_ONLY,
        block: (Folder, UIDFolder) -> T,
    ): T = withFolder(account, readTimeoutMillis, mode, MailFolderRole.INBOX, block)

    /** Папка "Отправленные": по служебной пометке \Sent, иначе по привычным названиям. */
    private fun resolveFolder(store: Store, role: MailFolderRole): Folder {
        if (role == MailFolderRole.INBOX) return store.getFolder("INBOX")

        val all = store.defaultFolder.list("*")
        return all.firstOrNull { folder ->
            (folder as? IMAPFolder)?.attributes?.any { attribute -> attribute.equals("\\Sent", ignoreCase = true) } == true
        } ?: all.firstOrNull { folder -> folder.name.lowercase() in SENT_FOLDER_NAMES }
        ?: throw MailFolderNotFoundException(role)
    }

    private fun <T> withFolder(
        account: MailAccountConfig,
        readTimeoutMillis: Int,
        mode: Int,
        role: MailFolderRole,
        block: (Folder, UIDFolder) -> T,
    ): T {
        val session = ImapSessionFactory.create(
            account = account,
            readTimeoutMillis = readTimeoutMillis,
        )
        val store = session.getStore("imaps")

        try {
            store.connect(
                account.host,
                account.port,
                account.username,
                account.password,
            )

            val inbox = resolveFolder(store, role)
            try {
                inbox.open(mode)
                val uidFolder = inbox as? UIDFolder
                    ?: error(
                        "Mailbox ${account.name} does not support IMAP UID",
                    )
                return block(inbox, uidFolder)
            } finally {
                if (inbox.isOpen) inbox.close(false)
            }
        } finally {
            if (store.isConnected) store.close()
        }
    }

    private companion object {
        val BULK_HEADERS = listOf("List-Unsubscribe", "List-Id", "Precedence", "Auto-Submitted")
        const val METADATA_TIMEOUT_MILLIS = 30_000
        val WHITESPACE = Regex("\\s+")
        const val CONTENT_TIMEOUT_MILLIS = 180_000
        const val MAX_VIEW_BODY_CHARS = 100_000
        val SENT_FOLDER_NAMES = setOf("sent", "sent items", "sent messages", "sent mail", "отправленные")
    }
}
