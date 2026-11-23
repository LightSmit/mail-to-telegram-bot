package io.github.lightsmit.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InboundMailServiceTest {

    @Test
    fun `tokens are short lowercase and unambiguous`() {
        repeat(100) {
            val token = InboundMailService.newToken()
            assertEquals(InboundMailService.TOKEN_LENGTH, token.length)
            assertTrue(token.all { char -> char in "abcdefghijkmnpqrstuvwxyz23456789" }, token)
        }
    }

    @Test
    fun `same message id means the same email`() {
        val first = "Message-ID: <abc@example.com>\r\nSubject: Hi\r\n\r\nBody".toByteArray()
        val redelivered = "Received: by relay\r\nMessage-ID: <abc@example.com>\r\nSubject: Hi\r\n\r\nBody".toByteArray()
        val other = "Message-ID: <other@example.com>\r\nSubject: Hi\r\n\r\nBody".toByteArray()

        assertEquals(InboundMailService.messageKey(first), InboundMailService.messageKey(redelivered))
        assertNotEquals(InboundMailService.messageKey(first), InboundMailService.messageKey(other))
    }

    @Test
    fun `email without message id is keyed by its content`() {
        val key = InboundMailService.messageKey("Subject: Hi\r\n\r\nBody".toByteArray())
        assertTrue(key.startsWith("raw:"))
    }
}
