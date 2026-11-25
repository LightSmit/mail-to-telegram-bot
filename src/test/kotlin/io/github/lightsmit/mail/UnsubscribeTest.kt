package io.github.lightsmit.mail

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnsubscribeTest {

    @Test
    fun `takes the https link and one-click flag`() {
        val link = Unsubscribe.parse(
            "<mailto:unsub@news.example.com?subject=stop>, <https://news.example.com/u?id=42&t=x>",
            "List-Unsubscribe=One-Click",
        )
        assertEquals("https://news.example.com/u?id=42&t=x", link?.url)
        assertTrue(link?.oneClick == true)
    }

    @Test
    fun `no one-click without the post header`() {
        val link = Unsubscribe.parse("<https://news.example.com/u>", null)
        assertFalse(link?.oneClick ?: true)
    }

    @Test
    fun `ignores mailto only, http and garbage`() {
        assertNull(Unsubscribe.parse("<mailto:unsub@example.com>", "List-Unsubscribe=One-Click"))
        assertNull(Unsubscribe.parse("<http://example.com/u>", null))
        assertNull(Unsubscribe.parse("https://example.com/u", null))
        assertNull(Unsubscribe.parse("<https://user:pass@example.com/u>", null))
        assertNull(Unsubscribe.parse(null, null))
    }

    @Test
    fun `internal addresses are not public`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.5", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "::ffff:10.0.0.1")
            .forEach { value -> assertFalse(Unsubscribe.isPublicAddress(InetAddress.getByName(value)), value) }
        listOf("1.1.1.1", "13.140.92.222", "2606:4700:4700::1111")
            .forEach { value -> assertTrue(Unsubscribe.isPublicAddress(InetAddress.getByName(value)), value) }
    }
}
