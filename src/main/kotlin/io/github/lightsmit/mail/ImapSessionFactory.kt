package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import jakarta.mail.Session
import java.util.Properties

object ImapSessionFactory {

    fun create(
        account: MailAccountConfig,
        readTimeoutMillis: Int,
    ): Session {
        require(readTimeoutMillis > 0) {
            "IMAP read timeout must be greater than zero"
        }

        val properties = Properties().apply {
            setProperty("mail.store.protocol", "imaps")
            setProperty("mail.imaps.host", account.host)
            setProperty(
                "mail.imaps.port",
                account.port.toString(),
            )

            setProperty("mail.imaps.ssl.enable", "true")
            setProperty(
                "mail.imaps.ssl.checkserveridentity",
                "true",
            )

            // Чтение содержимого не помечает письмо прочитанным.
            setProperty("mail.imaps.peek", "true")

            setProperty(
                "mail.imaps.connectiontimeout",
                CONNECTION_TIMEOUT_MILLIS.toString(),
            )

            setProperty(
                "mail.imaps.timeout",
                readTimeoutMillis.toString(),
            )

            setProperty(
                "mail.imaps.writetimeout",
                WRITE_TIMEOUT_MILLIS.toString(),
            )
        }

        return Session.getInstance(properties)
    }

    private const val CONNECTION_TIMEOUT_MILLIS = 10_000
    private const val WRITE_TIMEOUT_MILLIS = 10_000
}