package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.search.FlagTerm
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import java.nio.file.Path

class ImapMailClient(
    private val attachmentTempDirectory: Path,
    private val maxAttachmentSizeBytes: Long,
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(ImapMailClient::class.java)

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

    /**
     * Письмо, пришедшее пересылкой (исходный текст в формате MIME). full = false - только
     * заголовки для карточки, true - еще текст и вложения.
     */
    fun parseRaw(raw: ByteArray, uid: Long, full: Boolean): EmailSummary {
        val message = jakarta.mail.internet.MimeMessage(
            jakarta.mail.Session.getInstance(java.util.Properties()),
            java.io.ByteArrayInputStream(raw),
        )
        val summary = if (full) createFullSummary(message, uid) else createMetadataSummary(message, uid)
        // В пересланном письме нет даты получения ящиком: берем дату отправки или текущее время
        return if (summary.receivedAt != null) summary else summary.copy(receivedAt = summary.sentAt ?: java.time.Instant.now())
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
            senderAddress = senderAddress(message),
            unsubscribe = runCatching {
                Unsubscribe.parse(
                    message.getHeader("List-Unsubscribe")?.joinToString(", "),
                    message.getHeader("List-Unsubscribe-Post")?.firstOrNull(),
                )
            }.getOrNull(),
        )
    }

    private fun formatSender(message: Message): String {
        return message.from
            ?.joinToString(", ") { address ->
                (address as? InternetAddress)
                    ?.toUnicodeString()
                    ?: address.toString()
            }
            ?: UNKNOWN_SENDER
    }

    private fun formatSubject(message: Message): String {
        return message.subject
            ?.takeIf { it.isNotBlank() }
            ?: NO_SUBJECT
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
        // false - вернуть письму статус «непрочитано»
        seen: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        withInbox(account, METADATA_TIMEOUT_MILLIS, Folder.READ_WRITE) { _, uidFolder ->
            if (uidFolder.uidValidity != expectedUidValidity) {
                return@withInbox false
            }

            val message = uidFolder.getMessageByUID(uid)
                ?: return@withInbox false

            message.setFlag(Flags.Flag.SEEN, seen)
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
     * search - поиск по любой части темы, отправителя или получателя. Бот сам просматривает
     * письма пачками от новых к старым, не дольше SEARCH_BUDGET за запрос: медленная почта
     * (Mail.ru) отдает письма неспешно. Продолжение поиска - с курсора nextCursor.
     */
    suspend fun listMessages(
        account: MailAccountConfig,
        role: MailFolderRole,
        offset: Int,
        limit: Int,
        search: String?,
        unreadOnly: Boolean,
        // Поиск: с какого номера письма продолжить (null - с самого нового)
        cursor: Int? = null,
    ): MailListPage = withContext(Dispatchers.IO) {
        require(offset >= 0) { "Offset must not be negative" }
        require(limit in 1..100) { "Limit must be between 1 and 100" }

        withFolder(account, CONTENT_TIMEOUT_MILLIS, Folder.READ_ONLY, role) { folder, uidFolder ->
            val query = search?.trim()?.takeIf(String::isNotEmpty)

            if (query != null) {
                val scan = scanSearch(folder, query, unreadOnly, from = cursor, want = limit)
                val page = scan.messages.toTypedArray()
                if (page.isNotEmpty()) {
                    // Поиск читал только заголовки: для строк списка дочитываем найденные одним запросом
                    folder.fetch(
                        page,
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.CONTENT_INFO)
                            add(UIDFolder.FetchProfileItem.UID)
                        },
                    )
                }
                return@withFolder MailListPage(
                    uidValidity = uidFolder.uidValidity,
                    total = page.size,
                    items = page.map { message -> listItem(message, uidFolder.getUID(message)) },
                    hasMore = scan.nextCursor != null,
                    partial = scan.timedOut,
                    nextCursor = scan.nextCursor,
                    scanned = scan.scanned,
                    folderSize = scan.folderSize,
                )
            }

            // Без условий берем по номерам с конца папки
            val found: List<Message>? = when {
                unreadOnly -> folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false)).toList()
                else -> null
            }

            val total = found?.size ?: folder.messageCount
            val end = total - offset
            val start = maxOf(0, end - limit)

            val messages: Array<Message> = when {
                end <= 0 -> emptyArray()
                found != null -> found.subList(start, end).toTypedArray()
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

    /**
     * Письма с данными UID (отмеченные звездочкой), новые сверху. uidsFor получает текущий
     * UIDVALIDITY папки: если он сменился, старые отметки не подходят и список пуст.
     */
    suspend fun listByUids(
        account: MailAccountConfig,
        role: MailFolderRole,
        uidsFor: (Long) -> List<Long>,
    ): MailListPage = withContext(Dispatchers.IO) {
        withFolder(account, CONTENT_TIMEOUT_MILLIS, Folder.READ_ONLY, role) { folder, uidFolder ->
            val uids = uidsFor(uidFolder.uidValidity).take(MAX_UID_LIST)
            val messages = if (uids.isEmpty()) {
                emptyArray()
            } else {
                uidFolder.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
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
            val items = messages.map { message -> listItem(message, uidFolder.getUID(message)) }
                .sortedByDescending { item -> item.date }
            MailListPage(uidValidity = uidFolder.uidValidity, total = items.size, items = items, hasMore = false)
        }
    }

    private class SearchScan(
        val messages: List<Message>,
        // С какого номера продолжить; null - папка просмотрена до конца
        val nextCursor: Int?,
        val timedOut: Boolean,
        // Сколько писем просмотрено от начала поиска и сколько всего в папке
        val scanned: Int,
        val folderSize: Int,
    )

    // Новые письма первыми. Возвращает все совпадения из просмотренных пачек
    private fun scanSearch(folder: Folder, query: String, unreadOnly: Boolean, from: Int?, want: Int): SearchScan {
        val needle = normalizeForSearch(query)
        val count = folder.messageCount
        val deadline = System.nanoTime() + SEARCH_BUDGET_NANOS
        val found = mutableListOf<Message>()
        var end = (from ?: count).coerceAtMost(count)
        var timedOut = false
        // Пачка растет, пока почта отвечает быстро: меньше запросов на тот же объем
        var chunkSize = SEARCH_CHUNK
        val startedAt = System.nanoTime()

        while (end >= 1 && found.size < want) {
            if (System.nanoTime() > deadline) {
                timedOut = true
                break
            }
            val start = maxOf(1, end - chunkSize + 1)
            val chunk = folder.getMessages(start, end)
            val chunkStartedAt = System.nanoTime()
            // Только тема, отправитель и получатель: легче, чем ENVELOPE со всеми адресами и датами
            folder.fetch(
                chunk,
                FetchProfile().apply {
                    SEARCH_HEADERS.forEach { header -> add(header) }
                    add(FetchProfile.Item.FLAGS)
                },
            )
            chunk.reversed()
                .filter { message -> (!unreadOnly || !isSeen(message)) && matchesSearchHeaders(message, needle) }
                .forEach { message -> found.add(message) }
            end = start - 1
            val chunkMillis = (System.nanoTime() - chunkStartedAt) / 1_000_000
            if (chunkMillis < FAST_CHUNK_MILLIS) chunkSize = minOf(chunkSize * 2, MAX_SEARCH_CHUNK)
        }
        logger.info(
            "Search scanned {} of {} emails in {} ms (chunk {}, found {})",
            (from ?: count).coerceAtMost(count) - maxOf(end, 0),
            count,
            (System.nanoTime() - startedAt) / 1_000_000,
            chunkSize,
            found.size,
        )
        return SearchScan(
            messages = found,
            nextCursor = end.takeIf { value -> value >= 1 },
            timedOut = timedOut,
            scanned = count - maxOf(end, 0),
            folderSize = count,
        )
    }

    // Совпадение по заголовкам, прочитанным при поиске (без дозагрузки ENVELOPE по одному письму)
    private fun matchesSearchHeaders(message: Message, needle: String): Boolean {
        fun header(name: String): String? = runCatching { message.getHeader(name)?.joinToString(", ") }.getOrNull()
            ?.replace(Regex("\\r?\\n[ \\t]+"), " ")
            ?.let { value -> runCatching { MimeUtility.decodeText(value) }.getOrDefault(value) }

        val haystack = buildList {
            header("Subject")?.let(::add)
            listOf("From", "To").forEach { name ->
                val raw = header(name) ?: return@forEach
                add(raw)
                runCatching { InternetAddress.parseHeader(raw, false) }.getOrNull()?.forEach { address ->
                    address.personal?.let(::add)
                    address.address?.let(::add)
                }
            }
        }
        return haystack.any { value -> normalizeForSearch(value).contains(needle) }
    }

    private fun isSeen(message: Message): Boolean =
        runCatching { message.isSet(Flags.Flag.SEEN) }.getOrDefault(true)

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

            messageView(message, uid, seen = runCatching { message.isSet(Flags.Flag.SEEN) }.getOrDefault(true))
        }
    }

    /** Письмо ящика-пересылки (из базы) для просмотра в приложении. */
    fun readRaw(raw: ByteArray, uid: Long): MailMessageView {
        val message = jakarta.mail.internet.MimeMessage(
            jakarta.mail.Session.getInstance(java.util.Properties()),
            java.io.ByteArrayInputStream(raw),
        )
        return messageView(message, uid, seen = true)
    }

    private fun messageView(message: Message, uid: Long, seen: Boolean): MailMessageView {
        val content = EmailContentExtractor.extract(
            part = message,
            tempDirectory = attachmentTempDirectory,
            maxAttachmentSizeBytes = maxAttachmentSizeBytes,
            includeAttachments = false,
        )
        return MailMessageView(
            uid = uid,
            from = formatSender(message),
            to = formatRecipients(message, Message.RecipientType.TO),
            cc = formatRecipients(message, Message.RecipientType.CC),
            subject = formatSubject(message),
            date = (message.receivedDate ?: message.sentDate)?.toInstant(),
            body = content.body?.take(MAX_VIEW_BODY_CHARS),
            attachments = EmailContentExtractor.attachmentNames(message),
            seen = seen,
            html = EmailHtml.view(message),
        )
    }

    private fun listItem(message: Message, uid: Long): MailListItem {
        val sender = runCatching { message.from?.firstOrNull() as? InternetAddress }.getOrNull()
        return MailListItem(
            uid = uid,
            fromName = sender?.personal?.takeIf(String::isNotBlank)
                ?: sender?.address
                ?: "",
            fromAddress = sender?.address?.lowercase(),
            to = formatRecipients(message, Message.RecipientType.TO),
            subject = runCatching { message.subject }.getOrNull()?.takeIf(String::isNotBlank) ?: "",
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

    companion object {
        /** Заглушки для писем без темы и отправителя; на язык пользователя их переводит бот. */
        const val NO_SUBJECT = "(no subject)"
        const val UNKNOWN_SENDER = "(sender unknown)"

        private val BULK_HEADERS = listOf("List-Unsubscribe", "List-Id", "Precedence", "Auto-Submitted")
        private const val METADATA_TIMEOUT_MILLIS = 30_000
        private val WHITESPACE = Regex("\\s+")
        private const val CONTENT_TIMEOUT_MILLIS = 180_000
        private const val MAX_VIEW_BODY_CHARS = 100_000
        // Поиск: пачка писем за один запрос к почте и сколько ждем почту за один поиск
        private const val SEARCH_CHUNK = 100
        private const val MAX_SEARCH_CHUNK = 800
        private const val FAST_CHUNK_MILLIS = 2_000L
        private val SEARCH_HEADERS = listOf("Subject", "From", "To")
        private const val SEARCH_BUDGET_NANOS = 20_000_000_000L
        // Больше отмеченных писем за раз не показываем
        private const val MAX_UID_LIST = 500
        private val SENT_FOLDER_NAMES = setOf("sent", "sent items", "sent messages", "sent mail", "отправленные")
    }
}

/** Для поиска: без учета регистра, буква \u0451 приравнена к "е", лишние пробелы схлопнуты. */
internal fun normalizeForSearch(value: String): String =
    value.lowercase().replace('\u0451', '\u0435').replace(Regex("\\s+"), " ").trim()
