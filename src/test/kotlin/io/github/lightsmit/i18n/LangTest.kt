package io.github.lightsmit.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LangTest {

    @Test
    fun `picks language from telegram code`() {
        assertEquals(Lang.RU, Lang.fromCode("ru"))
        assertEquals(Lang.RU, Lang.fromCode("uk"))
        assertEquals(Lang.RU, Lang.fromCode(null))
        assertEquals(Lang.EN, Lang.fromCode("en-US"))
        assertEquals(Lang.EN, Lang.fromCode("de"))
    }

    @Test
    fun `counts files in both languages`() {
        assertEquals("1 файл", RuTexts.attachmentCount(1))
        assertEquals("3 файла", RuTexts.attachmentCount(3))
        assertEquals("11 файлов", RuTexts.attachmentCount(11))
        assertEquals("1 file", EnTexts.attachmentCount(1))
        assertEquals("5 files", EnTexts.attachmentCount(5))
    }

    @Test
    fun `help mentions reply only when it is enabled`() {
        assertTrue(EnTexts.help(replyEnabled = true).contains("Reply"))
        assertFalse(EnTexts.help(replyEnabled = false).contains("Reply"))
    }
}
