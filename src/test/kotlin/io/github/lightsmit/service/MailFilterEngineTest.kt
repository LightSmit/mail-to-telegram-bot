package io.github.lightsmit.service

import io.github.lightsmit.config.MailFilters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailFilterEngineTest {

    private val filters = MailFilters(
        skipBulk = true,
        blockedSenders = listOf("news@shop.ru", "@example.com"),
        blockedSubjectWords = listOf("скидка"),
    )

    @Test
    fun `passes ordinary mail`() {
        assertNull(MailFilterEngine.check(filters, "boss@work.ru", "Отчет за неделю", isBulk = false))
        assertNull(MailFilterEngine.check(MailFilters(), "news@shop.ru", "Скидка", isBulk = true))
    }

    @Test
    fun `pause blocks everything`() {
        assertEquals(
            MailFilterReason.PAUSED,
            MailFilterEngine.check(MailFilters(paused = true), "boss@work.ru", "Отчет", isBulk = false),
        )
    }

    @Test
    fun `skips bulk mail only when enabled`() {
        assertEquals(MailFilterReason.BULK, MailFilterEngine.check(filters, "boss@work.ru", "Отчет", isBulk = true))
        assertNull(MailFilterEngine.check(filters.copy(skipBulk = false), "boss@work.ru", "Отчет", isBulk = true))
    }

    @Test
    fun `matches senders by address and domain`() {
        assertEquals(MailFilterReason.BLOCKED_SENDER, MailFilterEngine.check(filters, "News@Shop.ru", "Привет", false))
        assertEquals(MailFilterReason.BLOCKED_SENDER, MailFilterEngine.check(filters, "a@example.com", "Привет", false))
        assertEquals(MailFilterReason.BLOCKED_SENDER, MailFilterEngine.check(filters, "a@mail.example.com", "Привет", false))
        assertNull(MailFilterEngine.check(filters, "a@notexample.com", "Привет", false))
        assertNull(MailFilterEngine.check(filters, "other@shop.ru", "Привет", false))
        assertNull(MailFilterEngine.check(filters, null, "Привет", false))
    }

    @Test
    fun `allow list lets through only listed senders`() {
        val onlyBoss = MailFilters(allowedSenders = listOf("boss@work.ru", "bank.ru"))
        assertNull(MailFilterEngine.check(onlyBoss, "Boss@Work.ru", "Отчет", false))
        assertNull(MailFilterEngine.check(onlyBoss, "info@online.bank.ru", "Выписка", false))
        assertEquals(MailFilterReason.NOT_ALLOWED_SENDER, MailFilterEngine.check(onlyBoss, "news@shop.ru", "Акция", false))
        assertEquals(MailFilterReason.NOT_ALLOWED_SENDER, MailFilterEngine.check(onlyBoss, null, "Без отправителя", false))
        assertEquals(
            MailFilterReason.BLOCKED_SUBJECT,
            MailFilterEngine.check(onlyBoss.copy(blockedSubjectWords = listOf("реклама")), "boss@work.ru", "Реклама", false),
        )
    }

    @Test
    fun `matches subject words ignoring case`() {
        assertEquals(MailFilterReason.BLOCKED_SUBJECT, MailFilterEngine.check(filters, "boss@work.ru", "Большая СКИДКА", false))
    }

    @Test
    fun `normalizes user input`() {
        assertEquals(
            listOf("news@shop.ru", "@example.com"),
            MailFilterEngine.normalizeSenders(listOf(" News@Shop.RU ", "@example.com", "bad entry", "news@shop.ru", "")),
        )
        assertEquals(listOf("Скидка"), MailFilterEngine.normalizeWords(listOf("Скидка", "скидка", "  ")))
        assertEquals(MailFilterEngine.MAX_ENTRIES, MailFilterEngine.normalizeWords((1..80).map { "w$it" }).size)
    }
}
