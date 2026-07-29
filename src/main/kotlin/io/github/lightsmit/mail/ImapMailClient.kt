package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

class ImapMailClient {

    suspend fun fetchCursor(
        account: MailAccountConfig,
    ): MailboxCursor = withContext(Dispatchers.IO) {
        withInbox(account) { inbox, uidFolder ->
            MailboxCursor(
                uidValidity = uidFolder.uidValidity,
                latestUid = findLatestUid(inbox, uidFolder),
            )
        }
    }

    suspend fun fetchAfterUid(
        account: MailAccountConfig,
        afterUid: Long,
        limit: Int,
    ): MailboxBatch = withContext(Dispatchers.IO) {
        require(afterUid >= 0) {
            "UID must not be negative"
        }

        require(limit > 0) {
            "Message limit must be greater than zero"
        }

        withInbox(account) { inbox, uidFolder ->
            val uidValidity = uidFolder.uidValidity
            val latestUid = findLatestUid(inbox, uidFolder)

            if (latestUid == 0L || latestUid <= afterUid) {
                return@withInbox MailboxBatch(
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
                return@withInbox MailboxBatch(
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
                .sortedBy(uidFolder::getUID)
                .take(limit)
                .map { message ->
                    EmailSummary(
                        uid = uidFolder.getUID(message),

                        from = message.from
                            ?.joinToString(", ") { address ->
                                (address as? InternetAddress)
                                    ?.toUnicodeString()
                                    ?: address.toString()
                            }
                            ?: "(sender unknown)",

                        subject = message.subject
                            ?.takeIf(String::isNotBlank)
                            ?: "(no subject)",

                        sentAt = message.sentDate?.toInstant(),
                    )
                }

            MailboxBatch(
                uidValidity = uidValidity,
                latestUid = latestUid,
                messages = summaries,
            )
        }
    }

    private fun findLatestUid(
        inbox: Folder,
        uidFolder: UIDFolder,
    ): Long {
        if (inbox.messageCount == 0) {
            return 0
        }

        val lastMessage = inbox.getMessage(inbox.messageCount)
        return uidFolder.getUID(lastMessage)
    }

    private fun <T> withInbox(
        account: MailAccountConfig,
        block: (Folder, UIDFolder) -> T,
    ): T {
        val properties = Properties().apply {
            setProperty("mail.store.protocol", "imaps")
            setProperty("mail.imaps.host", account.host)
            setProperty("mail.imaps.port", account.port.toString())
            setProperty("mail.imaps.ssl.enable", "true")
            setProperty("mail.imaps.ssl.checkserveridentity", "true")

            setProperty("mail.imaps.connectiontimeout", "10000")
            setProperty("mail.imaps.timeout", "10000")
            setProperty("mail.imaps.writetimeout", "10000")
        }

        val session = Session.getInstance(properties)
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
                if (inbox.isOpen) {
                    inbox.close(false)
                }
            }
        } finally {
            if (store.isConnected) {
                store.close()
            }
        }
    }
}