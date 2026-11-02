package io.github.lightsmit.service

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationScheduleTest {

    private val moscowNight = NotificationSettings(
        timezone = "Europe/Moscow",
        quietEnabled = true,
        quietStartMinutes = 23 * 60,
        quietEndMinutes = 8 * 60,
    )

    private fun at(value: String) = Instant.parse(value)

    @Test
    fun `quiet hours cross midnight`() {
        assertTrue(NotificationSchedule.isQuiet(moscowNight, at("2026-11-02T20:30:00Z"))) // 23:30 МСК
        assertTrue(NotificationSchedule.isQuiet(moscowNight, at("2026-11-02T04:59:00Z"))) // 07:59
        assertFalse(NotificationSchedule.isQuiet(moscowNight, at("2026-11-02T05:00:00Z"))) // 08:00
        assertFalse(NotificationSchedule.isQuiet(moscowNight.copy(quietEnabled = false), at("2026-11-02T20:30:00Z")))
    }

    @Test
    fun `digest slots are anchored to nine in the morning`() {
        fun next(hours: Int, now: String) =
            NotificationSchedule.nextDigestAt(NotificationSettings(timezone = "Europe/Moscow", digestHours = hours), at(now))

        assertNull(next(0, "2026-11-02T12:00:00Z"))
        assertEquals(at("2026-11-02T06:00:00Z"), next(24, "2026-11-02T05:00:00Z")) // сегодня в 9:00
        assertEquals(at("2026-11-03T06:00:00Z"), next(24, "2026-11-02T06:00:00Z")) // завтра в 9:00
        assertEquals(at("2026-11-02T18:00:00Z"), next(12, "2026-11-02T12:00:00Z")) // 21:00
        assertEquals(at("2026-11-02T13:00:00Z"), next(1, "2026-11-02T12:10:00Z"))
        assertEquals(at("2026-11-03T00:00:00Z"), next(6, "2026-11-02T21:30:00Z")) // 03:00 следующего дня
    }

    @Test
    fun `normalizes and parses user input`() {
        val normalized = NotificationSchedule.normalize(NotificationSettings(timezone = "Mars/Base", digestHours = 5))
        assertEquals("Europe/Moscow", normalized.timezone)
        assertEquals(0, normalized.digestHours)
        assertEquals(1410, NotificationSchedule.parseMinutes("23:30"))
        assertNull(NotificationSchedule.parseMinutes("24:00"))
        assertEquals("08:00", NotificationSchedule.formatMinutes(480))
    }
}
