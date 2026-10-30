package io.github.lightsmit.mail

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.service.MailHostPolicy
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.Date
import java.util.Properties

/** Сервер исходящей почты. implicitTls: true - SSL сразу (порт 465), false - STARTTLS (порт 587). */
data class SmtpEndpoint(
    val host: String,
    val port: Int,
    val implicitTls: Boolean,
)

object SmtpEndpoints {

    private val known = mapOf(
        "imap.gmail.com" to SmtpEndpoint("smtp.gmail.com", 465, implicitTls = true),
        "imap.mail.ru" to SmtpEndpoint("smtp.mail.ru", 465, implicitTls = true),
        "imap.yandex.ru" to SmtpEndpoint("smtp.yandex.ru", 465, implicitTls = true),
        "imap.yandex.com" to SmtpEndpoint("smtp.yandex.com", 465, implicitTls = true),
        "imap.rambler.ru" to SmtpEndpoint("smtp.rambler.ru", 465, implicitTls = true),
        "imap.mail.me.com" to SmtpEndpoint("smtp.mail.me.com", 587, implicitTls = false),
    )

    /** SMTP-сервер для IMAP-сервера ящика: известные сервисы из списка, остальные по шаблону imap.X -> smtp.X. */
    fun forImapHost(imapHost: String): SmtpEndpoint? {
        val host = imapHost.trim().lowercase()
        known[host]?.let { endpoint -> return endpoint }
        if (host.startsWith("imap.")) {
            return SmtpEndpoint("smtp." + host.removePrefix("imap."), 465, implicitTls = true)
        }
        return null
    }
}

/** Ответ, готовый к отправке. */
data class OutgoingReply(
    val to: List<InternetAddress>,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: String?,
)

class SmtpMailSender {

    /** Отправляет письмо от имени ящика. Ошибки (вход, сеть) пробрасываются как есть. */
    suspend fun send(
        account: MailAccountConfig,
        endpoint: SmtpEndpoint,
        reply: OutgoingReply,
    ) = withContext(Dispatchers.IO) {
        require(reply.to.isNotEmpty()) { "Reply has no recipients" }

        // Тот же запрет, что и для IMAP: не подключаемся к внутренним адресам
        val addresses = InetAddress.getAllByName(endpoint.host)
        require(addresses.isNotEmpty() && addresses.all(MailHostPolicy::isPublicAddress)) {
            "SMTP host is not public"
        }

        val protocol = if (endpoint.implicitTls) "smtps" else "smtp"
        val properties = Properties().apply {
            setProperty("mail.transport.protocol", protocol)
            setProperty("mail.$protocol.host", endpoint.host)
            setProperty("mail.$protocol.port", endpoint.port.toString())
            setProperty("mail.$protocol.auth", "true")
            setProperty("mail.$protocol.ssl.checkserveridentity", "true")
            if (!endpoint.implicitTls) {
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
            }
            setProperty("mail.$protocol.connectiontimeout", TIMEOUT_MILLIS.toString())
            setProperty("mail.$protocol.timeout", TIMEOUT_MILLIS.toString())
            setProperty("mail.$protocol.writetimeout", TIMEOUT_MILLIS.toString())
        }

        val session = Session.getInstance(properties)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(account.username))
            setRecipients(Message.RecipientType.TO, reply.to.toTypedArray())
            setSubject(reply.subject, "UTF-8")
            setText(reply.body, "UTF-8")
            sentDate = Date()
            reply.inReplyTo?.let { value -> setHeader("In-Reply-To", value) }
            reply.references?.let { value -> setHeader("References", value) }
        }
        message.saveChanges()

        val transport = session.getTransport(protocol)
        try {
            transport.connect(endpoint.host, endpoint.port, account.username, account.password)
            transport.sendMessage(message, message.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000
    }
}
