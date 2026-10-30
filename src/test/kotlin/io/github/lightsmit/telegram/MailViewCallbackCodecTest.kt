package io.github.lightsmit.telegram

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.service.MailAccountRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailViewCallbackCodecTest {

    private fun account(owner: Long, id: Long = 1) = MailAccountConfig(
        name = "Box",
        host = "imap.mail.ru",
        port = 993,
        username = "user@mail.ru",
        password = "secret",
        ownerChatId = owner,
        id = id,
    )

    @Test
    fun `same mailbox of different owners gets different codes`() {
        assertNotEquals(
            MailViewCallbackCodec.accountCode(account(owner = 1)),
            MailViewCallbackCodec.accountCode(account(owner = 2)),
        )
    }

    @Test
    fun `callback data fits telegram limit and round trips`() {
        val data = MailViewCallbackCodec.encode(MailViewAction.TEXT, account(owner = 9_999_999_999), 1_234_567_890, 9_876_543_210)
        assertTrue(data.toByteArray().size <= 64)
        val decoded = MailViewCallbackCodec.decode(data)!!
        assertEquals(MailViewAction.TEXT, decoded.action)
        assertEquals(9_876_543_210, decoded.uid)
    }

    @Test
    fun `registry finds mailbox only for its owner and understands legacy codes`() {
        val registry = MailAccountRegistry()
        val owned = account(owner = 42)
        registry.put(owned)

        assertEquals(owned, registry.findForCallback(MailViewCallbackCodec.accountCode(owned), 42))
        assertEquals(owned, registry.findForCallback(MailViewCallbackCodec.legacyAccountCode(owned), 42))
        assertNull(registry.findForCallback(MailViewCallbackCodec.accountCode(owned), 43))
    }

    @Test
    fun `password is not printed`() {
        assertTrue("secret" !in account(owner = 1).toString())
    }
}
