package io.github.lightsmit.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailSearchTest {

    @Test
    fun `search ignores case, extra spaces and the letter yo`() {
        assertEquals("отчет за неделю", normalizeForSearch("  Отч\u0451т   за НЕДЕЛЮ "))
    }

    @Test
    fun `search matches any part of a word`() {
        assertTrue(normalizeForSearch("22222").contains(normalizeForSearch("222")))
        assertTrue(normalizeForSearch("Re: Ваш ЗАКАЗ доставлен").contains(normalizeForSearch("заказ дост")))
    }
}
