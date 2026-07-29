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

    suspend fun fetchLatest(
        account: MailAccountConfig,
        limit: Int,
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        require(limit > 0) {
            "Message limit must be greater than zero"
        }

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

                val messageCount = inbox.messageCount

                if (messageCount == 0) {
                    return@withContext emptyList()
                }

                val firstMessageNumber = maxOf(
                    1,
                    messageCount - limit + 1,
                )

                val messages = inbox.getMessages(
                    firstMessageNumber,
                    messageCount,
                )

                val fetchProfile = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(UIDFolder.FetchProfileItem.UID)
                }

                inbox.fetch(messages, fetchProfile)

                val uidFolder = inbox as UIDFolder

                messages
                    .reversed()
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
                                ?.takeIf { it.isNotBlank() }
                                ?: "(no subject)",

                            sentAt = message.sentDate?.toInstant(),
                        )
                    }
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