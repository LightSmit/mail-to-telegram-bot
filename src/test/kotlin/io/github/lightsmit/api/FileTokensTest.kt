package io.github.lightsmit.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileTokensTest {

    private var now = Instant.parse("2026-11-25T10:00:00Z")
    private val key = ByteArray(32) { index -> index.toByte() }
    private val tokens = FileTokens(ttlSeconds = 600, clock = { now }, key = key)

    @Test
    fun `issued token opens the same file`() {
        val ticket = assertNotNull(tokens.verify(tokens.issue(7, 12, 5, 9001, 2)))
        assertEquals(7L, ticket.ownerId)
        assertEquals(12L, ticket.accountId)
        assertEquals(5L, ticket.uidValidity)
        assertEquals(9001L, ticket.uid)
        assertEquals(2, ticket.index)
    }

    @Test
    fun `token expires`() {
        val token = tokens.issue(7, 12, 5, 9001, 0)
        now = now.plusSeconds(601)
        assertNull(tokens.verify(token))
    }

    @Test
    fun `changed payload or foreign key is rejected`() {
        val token = tokens.issue(7, 12, 5, 9001, 0)
        val other = tokens.issue(8, 12, 5, 9001, 0)
        // Чужая полезная часть с подписью от другой ссылки
        val forged = other.substringBefore('.') + "." + token.substringAfter('.')
        assertNull(tokens.verify(forged))
        assertNull(FileTokens(clock = { now }).verify(token))
        assertNull(tokens.verify("garbage"))
        assertNull(tokens.verify(""))
    }
}
