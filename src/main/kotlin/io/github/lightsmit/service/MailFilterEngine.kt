package io.github.lightsmit.service

import io.github.lightsmit.config.MailFilters

/** Почему письмо не прислано (для логов). null - присылать. */
enum class MailFilterReason { PAUSED, NOT_ALLOWED_SENDER, BULK, BLOCKED_SENDER, BLOCKED_SUBJECT, OVER_LIMIT }

object MailFilterEngine {

    const val MAX_ENTRIES = 50
    const val MAX_ENTRY_LENGTH = 100

    fun check(
        filters: MailFilters,
        senderAddress: String?,
        subject: String,
        isBulk: Boolean,
    ): MailFilterReason? {
        val sender = senderAddress?.trim()?.lowercase()
        // Важный отправитель проходит всегда: и на паузе, и мимо остальных фильтров
        if (isVip(filters, sender)) return null

        if (filters.isPausedAt()) return MailFilterReason.PAUSED

        // "Присылать только от": письма от остальных (и без понятного отправителя) не присылаем
        if (filters.allowedSenders.isNotEmpty() &&
            (sender == null || filters.allowedSenders.none { rule -> senderMatches(sender, rule) })
        ) {
            return MailFilterReason.NOT_ALLOWED_SENDER
        }

        if (filters.skipBulk && isBulk) return MailFilterReason.BULK

        if (sender != null && filters.blockedSenders.any { rule -> senderMatches(sender, rule) }) {
            return MailFilterReason.BLOCKED_SENDER
        }

        val lowerSubject = subject.lowercase()
        if (filters.blockedSubjectWords.any { word -> word.isNotBlank() && lowerSubject.contains(word.lowercase()) }) {
            return MailFilterReason.BLOCKED_SUBJECT
        }

        return null
    }

    /** Письмо от важного отправителя (адрес или домен из vipSenders). */
    fun isVip(filters: MailFilters, senderAddress: String?): Boolean {
        val sender = senderAddress?.trim()?.lowercase() ?: return false
        return filters.vipSenders.any { rule -> senderMatches(sender, rule) }
    }

    /** Адрес из строки «Имя <name@example.com>» (так отправитель хранится в карточке). */
    fun addressOf(from: String?): String? {
        val text = from?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val inBrackets = Regex("<([^<>\\s]+@[^<>\\s]+)>").findAll(text).lastOrNull()?.groupValues?.get(1)
        val address = inBrackets ?: text.takeIf { value -> '@' in value && ' ' !in value }
        return address?.lowercase()
    }

    // "name@example.com" - точный адрес; "example.com" или "@example.com" - домен и его поддомены
    private fun senderMatches(sender: String, rawRule: String): Boolean {
        val rule = rawRule.trim().lowercase()
        if (rule.isEmpty()) return false

        if ('@' in rule && !rule.startsWith("@")) {
            return sender == rule
        }

        val domain = rule.removePrefix("@")
        val senderDomain = sender.substringAfterLast('@', missingDelimiterValue = "")
        return senderDomain == domain || senderDomain.endsWith(".$domain")
    }

    /** Чистит списки из приложения: пробелы, дубли, пустые строки, ограничение длины и количества. */
    fun normalizeSenders(values: List<String>): List<String> =
        values.map { value -> value.trim().lowercase() }
            .filter { value -> value.isNotEmpty() && value.length <= MAX_ENTRY_LENGTH && ' ' !in value }
            .distinct()
            .take(MAX_ENTRIES)

    fun normalizeWords(values: List<String>): List<String> =
        values.map { value -> value.trim() }
            .filter { value -> value.isNotEmpty() && value.length <= MAX_ENTRY_LENGTH }
            .distinctBy { value -> value.lowercase() }
            .take(MAX_ENTRIES)
}
