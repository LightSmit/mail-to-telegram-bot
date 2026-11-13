package io.github.lightsmit.service

import io.github.lightsmit.i18n.Lang
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPasswordHintsTest {
    private val t = Lang.RU.texts

    @Test
    fun `gmail regular password gets the app password hint`() {
        assertEquals(t.loginRejectedGmailRegular, AppPasswordHints.rejectionText("imap.gmail.com", "MyPassword123", t))
        assertEquals(t.loginRejectedGmailRegular, AppPasswordHints.rejectionText("imap.gmail.com", "abcdefghijklmno", t))
    }

    @Test
    fun `gmail app password that was rejected gets the revoked hint`() {
        assertEquals(t.loginRejectedGmail, AppPasswordHints.rejectionText("IMAP.gmail.com", "abcdefghijklmnop", t))
    }

    @Test
    fun `other providers get their own hints`() {
        assertEquals(t.loginRejectedYandex, AppPasswordHints.rejectionText("imap.yandex.ru", "secret", t))
        assertEquals(t.loginRejectedMailru, AppPasswordHints.rejectionText("imap.mail.ru", "secret", t))
        assertEquals(t.loginRejected, AppPasswordHints.rejectionText("imap.example.com", "secret", t))
    }
}
