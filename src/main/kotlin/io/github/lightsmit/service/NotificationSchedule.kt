package io.github.lightsmit.service

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Как пользователь хочет получать письма. Время - в его часовом поясе. */
data class NotificationSettings(
    val timezone: String = NotificationSchedule.DEFAULT_TIMEZONE,
    /** Тихие часы: письма приходят, но без звука. */
    val quietEnabled: Boolean = false,
    val quietStartMinutes: Int = 23 * 60,
    val quietEndMinutes: Int = 8 * 60,
    /** 0 - каждое письмо сразу; иначе сводкой раз в столько часов. */
    val digestHours: Int = 0,
)

object NotificationSchedule {

    const val DEFAULT_TIMEZONE = "Europe/Moscow"

    /** Допустимые интервалы сводки (часы). 0 - без сводки. */
    val DIGEST_OPTIONS = listOf(0, 1, 3, 6, 12, 24)

    /** Сводки привязаны к 9:00: раз в день - в 9:00, раз в 12 часов - в 9:00 и 21:00 и т.д. */
    const val DIGEST_ANCHOR_HOUR = 9

    fun zone(settings: NotificationSettings): ZoneId =
        runCatching { ZoneId.of(settings.timezone) }.getOrDefault(ZoneId.of(DEFAULT_TIMEZONE))

    /** Приводит настройки из приложения к допустимым значениям. */
    fun normalize(settings: NotificationSettings): NotificationSettings = settings.copy(
        timezone = settings.timezone.takeIf { value -> runCatching { ZoneId.of(value) }.isSuccess } ?: DEFAULT_TIMEZONE,
        quietStartMinutes = settings.quietStartMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        quietEndMinutes = settings.quietEndMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        digestHours = settings.digestHours.takeIf { hours -> hours in DIGEST_OPTIONS } ?: 0,
    )

    /** Сейчас тихие часы? Интервал может переходить через полночь (23:00-08:00). */
    fun isQuiet(settings: NotificationSettings, now: Instant): Boolean {
        if (!settings.quietEnabled) return false
        val start = settings.quietStartMinutes
        val end = settings.quietEndMinutes
        if (start == end) return false

        val local = now.atZone(zone(settings)).toLocalTime()
        val minutes = local.hour * 60 + local.minute
        return if (start < end) minutes in start until end else minutes >= start || minutes < end
    }

    /** Когда отправить письмо, пришедшее сейчас: null - сразу, иначе ближайшее время сводки. */
    fun nextDigestAt(settings: NotificationSettings, now: Instant): Instant? {
        val hours = settings.digestHours
        if (hours <= 0) return null

        val zone = zone(settings)
        val today = now.atZone(zone).toLocalDate()
        val slotHours = (0 until 24 / hours).map { index -> (DIGEST_ANCHOR_HOUR + index * hours) % 24 }

        return (0L..1L)
            .flatMap { dayOffset ->
                slotHours.map { hour ->
                    ZonedDateTime.of(today.plusDays(dayOffset), LocalTime.of(hour, 0), zone).toInstant()
                }
            }
            .filter { slot -> slot.isAfter(now) }
            .minOrNull()
    }

    fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** "23:30" -> 1410; null, если строка некорректна. */
    fun parseMinutes(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
