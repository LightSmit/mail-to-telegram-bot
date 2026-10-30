package io.github.lightsmit.mail

import jakarta.mail.internet.InternetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyFormatTest {

    private fun context(subject: String = "", messageId: String? = null, references: String? = null) =
        ReplyContext(
            recipients = listOf(InternetAddress("boss@work.ru")),
            subject = subject,
            messageId = messageId,
            references = references,
        )

    @Test
    fun `adds Re only once`() {
        assertEquals("Re: Отчет", ReplyFormat.subject("Отчет"))
        assertEquals("Re: Отчет", ReplyFormat.subject("Re: Отчет"))
        assertEquals("RE: Отчет", ReplyFormat.subject("RE: Отчет"))
        assertEquals("Ответ: Отчет", ReplyFormat.subject("Ответ: Отчет"))
        assertEquals("Re: (без темы)", ReplyFormat.subject("  "))
        assertEquals("Re: две строки", ReplyFormat.subject("две\r\nстроки"))
    }

    @Test
    fun `builds references chain`() {
        assertEquals("<a@x> <b@x>", ReplyFormat.references(context(messageId = "<b@x>", references = "<a@x>")))
        assertEquals("<b@x>", ReplyFormat.references(context(messageId = "<b@x>")))
        assertNull(ReplyFormat.references(context()))

        val long = (1..30).joinToString(" ") { "<$it@x>" }
        val chain = ReplyFormat.references(context(messageId = "<last@x>", references = long))!!.split(' ')
        assertEquals(20, chain.size)
        assertEquals("<last@x>", chain.last())
    }

    @Test
    fun `finds smtp server for mailbox`() {
        assertEquals(SmtpEndpoint("smtp.gmail.com", 465, true), SmtpEndpoints.forImapHost("imap.gmail.com"))
        assertEquals(SmtpEndpoint("smtp.mail.ru", 465, true), SmtpEndpoints.forImapHost("IMAP.mail.ru"))
        assertEquals(SmtpEndpoint("smtp.mail.me.com", 587, false), SmtpEndpoints.forImapHost("imap.mail.me.com"))
        assertEquals(SmtpEndpoint("smtp.example.org", 465, true), SmtpEndpoints.forImapHost("imap.example.org"))
        assertNull(SmtpEndpoints.forImapHost("mail.example.org"))
    }
}
