package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.UIDFolder
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
        )
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

    private fun <T> withInbox(
        account: MailAccountConfig,
        readTimeoutMillis: Int,
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

            val inbox = store.getFolder("INBOX")
            try {
                inbox.open(Folder.READ_ONLY)
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
        const val METADATA_TIMEOUT_MILLIS = 30_000
        const val CONTENT_TIMEOUT_MILLIS = 180_000
    }
}
