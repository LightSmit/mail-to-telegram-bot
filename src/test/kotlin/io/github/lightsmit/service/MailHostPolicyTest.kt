package io.github.lightsmit.service

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailHostPolicyTest {

    @Test
    fun `normalizes valid hostnames`() {
        assertEquals("imap.gmail.com", MailHostPolicy.normalizeHostname("  IMAP.Gmail.com. "))
        assertEquals("imap.mail.ru", MailHostPolicy.normalizeHostname("imap.mail.ru"))
    }

    @Test
    fun `rejects ip literals and garbage`() {
        assertNull(MailHostPolicy.normalizeHostname("127.0.0.1"))
        assertNull(MailHostPolicy.normalizeHostname("localhost"))
        assertNull(MailHostPolicy.normalizeHostname("mail-bot"))
        assertNull(MailHostPolicy.normalizeHostname("imap.gmail.com:993"))
        assertNull(MailHostPolicy.normalizeHostname("http://imap.gmail.com"))
        assertNull(MailHostPolicy.normalizeHostname(""))
    }

    @Test
    fun `private and special addresses are not public`() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.17.0.2", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "0.0.0.0", "198.18.0.1", "::1", "fd00::1", "fe80::1",
        ).forEach { literal ->
            assertFalse(MailHostPolicy.isPublicAddress(InetAddress.getByName(literal)), literal)
        }
    }

    @Test
    fun `public addresses are allowed`() {
        listOf("8.8.8.8", "94.100.180.70", "2a00:1450:4010:c05::6d").forEach { literal ->
            assertTrue(MailHostPolicy.isPublicAddress(InetAddress.getByName(literal)), literal)
        }
    }
}
