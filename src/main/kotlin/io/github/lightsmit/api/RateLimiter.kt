package io.github.lightsmit.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Ограничение частоты запросов в памяти: не больше [limit] запросов за [windowSeconds] на ключ
 * (обычно id пользователя Telegram). Скользящее окно по отметкам времени.
 * После перезапуска счетчики обнуляются - для защиты от перебора и флуда этого достаточно.
 */
class RateLimiter(
    private val limit: Int,
    private val windowSeconds: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hits = ConcurrentHashMap<Long, ArrayDeque<Long>>()

    /** true - запрос можно выполнять (и он засчитан), false - лимит исчерпан. */
    fun tryAcquire(key: Long): Boolean {
        val now = clock()
        val from = now - windowSeconds * 1000
        if (hits.size > MAX_KEYS) cleanup(from)
        val queue = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.first() <= from) queue.removeFirst()
            if (queue.size >= limit) return false
            queue.addLast(now)
            return true
        }
    }

    /** Через сколько секунд освободится место (для заголовка Retry-After). */
    fun retryAfterSeconds(key: Long): Long {
        val queue = hits[key] ?: return 0
        val oldest = synchronized(queue) { queue.firstOrNull() } ?: return 0
        val millis = oldest + windowSeconds * 1000 - clock()
        return ((millis + 999) / 1000).coerceAtLeast(1)
    }

    private fun cleanup(from: Long) {
        hits.entries.removeIf { entry ->
            synchronized(entry.value) { entry.value.lastOrNull()?.let { last -> last <= from } ?: true }
        }
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
