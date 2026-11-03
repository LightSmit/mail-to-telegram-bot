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
        if (filters.paused) return MailFilterReason.PAUSED

        val sender = senderAddress?.trim()?.lowercase()
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
