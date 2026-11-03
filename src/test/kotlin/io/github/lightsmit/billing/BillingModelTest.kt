package io.github.lightsmit.billing

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.config.MailFilters
import io.github.lightsmit.mail.OtpExtractor
import io.github.lightsmit.service.MailboxQuota
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BillingModelTest {

    @Test
    fun `grant terms`() {
        assertEquals(GrantTerm.Forever, GrantTerm.parse("forever"))
        assertEquals(GrantTerm.Forever, GrantTerm.parse(" Навсегда "))
        assertEquals("1m", GrantTerm.parse("1M")?.spec)
        assertEquals("3m", GrantTerm.parse("3м")?.spec)
        assertEquals("1y", GrantTerm.parse("1г")?.spec)
        assertEquals("14d", GrantTerm.parse("14d")?.spec)
        listOf("", "0m", "m", "1w", "1000d", "-1m", "1 month").forEach { value ->
            assertNull(GrantTerm.parse(value), "term '$value'")
        }

        val start = Instant.parse("2026-01-31T12:00:00Z")
        assertEquals(Instant.parse("2026-02-28T12:00:00Z"), assertIs<GrantTerm.Span>(GrantTerm.parse("1m")).addTo(start))
        assertEquals(Instant.parse("2027-01-31T12:00:00Z"), assertIs<GrantTerm.Span>(GrantTerm.parse("1y")).addTo(start))
    }

    @Test
    fun `offer payloads`() {
        Offer.entries.forEach { offer -> assertEquals(offer, Offer.fromPayload(offer.payload)) }
        assertNull(Offer.fromPayload("sub1:UNKNOWN"))
        assertNull(Offer.fromPayload("PRO_MONTH"))
        assertNull(Offer.fromPayload(null))
    }

    @Test
    fun `plans and limits`() {
        assertEquals(Plan.PRO, Plan.parse(" pro "))
        assertEquals(Plan.BUSINESS, Plan.parse("Business"))
        assertNull(Plan.parse("gold"))
        assertEquals(listOf(1, 5, 15), Plan.entries.map(Plan::maxMailboxes))
        assertEquals(Plan.PRO, maxOf(Plan.FREE, Plan.PRO))
    }

    @Test
    fun `over limit mailboxes are the newest active ones`() {
        fun box(id: Long, paused: Boolean = false) = MailAccountConfig(
            name = "m$id", host = "imap.mail.ru", port = 993, username = "m$id@mail.ru", password = "x",
            ownerChatId = 7, id = id, filters = MailFilters(paused = paused),
        )
        val accounts = listOf(box(3), box(1), box(2))
        assertEquals(setOf(2L, 3L), MailboxQuota.overLimit(accounts, 1))
        assertEquals(emptySet(), MailboxQuota.overLimit(accounts, 5))
        // Чтобы оставить ящик 3, остальные ставят на паузу
        assertEquals(emptySet(), MailboxQuota.overLimit(listOf(box(1, paused = true), box(2, paused = true), box(3)), 1))
        assertEquals(setOf(3L), MailboxQuota.overLimit(listOf(box(1, paused = true), box(2), box(3)), 1))
    }

    @Test
    fun `verification codes`() {
        val found = mapOf(
            "Ваш код подтверждения: 482913" to "482913",
            "Код для входа 5531" to "5531",
            "Your verification code is 739 204" to "739204",
            "Use code 120-554 to sign in" to "120554",
            "Одноразовый пароль: 66120018. Никому его не сообщайте" to "66120018",
            "Your Steam Guard code: 48213" to "48213",
            "OTP 834512 valid for 5 minutes" to "834512",
            "Код: 2026" to null,
            "Заказ 482913 оформлен" to null,
            "Shipping 48291 items" to null,
            "Код подтверждения придет по телефону +7 912 345-67-89" to null,
            "Если код не пришел, звоните 8 800 555-35-35" to null,
            "Your code expires at 12:30" to null,
            "Сумма 1500.00 руб, код операции не нужен" to null,
            "Новое письмо от Ивана" to null,
        )
        found.forEach { (text, expected) -> assertEquals(expected, OtpExtractor.find(text, null), text) }

        // Код в тексте письма, тема без кода
        assertEquals("990011", OtpExtractor.find("Вход в аккаунт", "Здравствуйте!\nВаш код: 990011\nОн действует 10 минут."))
        // Код из темы важнее
        assertEquals("111222", OtpExtractor.find("Code 111222", "code 333444"))
    }
}
