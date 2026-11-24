package io.github.lightsmit.service

import io.github.lightsmit.billing.ReferralService
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.storage.FunnelEvent
import io.github.lightsmit.storage.FunnelStore
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Шаги воронки нового пользователя по порядку. */
enum class FunnelStep(val key: String, val title: String) {
    START("start", "Запустили бота"),
    APP_OPEN("app_open", "Открыли приложение"),
    PROVIDER("provider", "Выбрали почту"),
    SUBMIT("submit", "Нажали «Подключить»"),
    CONNECT_ERROR("connect_error", "Получили ошибку подключения"),
    CONNECTED("connected", "Подключили ящик"),
    FIRST_MAIL("first_mail", "Получили первое письмо"),
    TRIAL("trial", "Включили пробный Pro"),
    PAID("paid", "Оплатили"),
    ;

    companion object {
        fun fromKey(key: String): FunnelStep? = entries.firstOrNull { step -> step.key == key }
    }
}

/** Итог воронки за период: сколько новых пользователей дошло до каждого шага. */
data class FunnelReport(
    val days: Int,
    val source: String?,
    val users: Int,
    val steps: Map<FunnelStep, Int>,
    // Откуда пришли, какую почту выбирали, какие были ошибки подключения
    val sources: Map<String, Int>,
    val providers: Map<String, Int>,
    val errors: Map<String, Int>,
)

/** Новый пользователь, который так и не подключил ящик: для /stuck. */
data class StuckUser(
    val userId: Long,
    val startedAt: Instant,
    val source: String?,
    val lastStep: FunnelStep,
    val error: String?,
)

/**
 * Воронка новых пользователей: запустил бота -> открыл приложение -> выбрал почту ->
 * нажал «Подключить» -> подключил ящик -> получил первое письмо -> пробный Pro -> оплата.
 * Новыми считаются только те, кто впервые написал боту после установки этой версии.
 * Ошибки записи только логируются: воронка никогда не мешает основной работе бота.
 */
class FunnelService(
    private val store: FunnelStore,
    // Когда человек впервые написал боту (null - неизвестно)
    private val firstSeen: suspend (Long) -> Instant?,
    // Админов из .env в отчетах не считаем: они тестируют бота
    private val excludedIds: Set<Long> = emptySet(),
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(FunnelService::class.java)

    // Шаги, уже записанные с момента запуска: чтобы не писать в базу при каждом запросе приложения
    private val recorded = ConcurrentHashMap.newKeySet<String>()

    /** /start с параметром: src_habr - источник habr, ref_... - приглашение друга, vip_... - ссылка-подарок. */
    suspend fun onStart(userId: Long, startArgument: String) {
        recordStartIfNew(userId, sourceOf(startArgument))
    }

    /** Приложение открыто. Если человек открыл его, ни разу не нажав /start, источник - app. */
    suspend fun onAppOpen(userId: Long) {
        recordStartIfNew(userId, SOURCE_APP)
        record(userId, FunnelStep.APP_OPEN)
    }

    suspend fun record(userId: Long, step: FunnelStep, detail: String? = null) {
        val key = "$userId:${step.key}"
        if (key in recorded) return
        safely(step) {
            store.insert(FunnelEvent(userId, step.key, detail?.take(DETAIL_MAX_LENGTH), clock()))
            recorded.add(key)
        }
    }

    suspend fun forget(userId: Long) {
        safely(null) { store.deleteUser(userId) }
        recorded.removeIf { key -> key.startsWith("$userId:") }
    }

    /** Шаги пользователя по порядку (с подробностями): для карточки в админ-панели. */
    suspend fun events(userId: Long): List<FunnelEvent> = store.eventsOf(userId)

    /** Последние события новых пользователей (без админов): для обзора в админ-панели. */
    suspend fun recentEvents(limit: Int): List<FunnelEvent> =
        store.recent(limit * 2).filter { event -> event.userId !in excludedIds }.take(limit)

    suspend fun report(days: Int, source: String? = null): FunnelReport {
        val cohort = cohort(days)
        val sources = cohort.values.mapNotNull { events -> events[FunnelStep.START]?.detail }.groupingBy { it }.eachCount()
        val selected = if (source == null) cohort else cohort.filterValues { events -> events[FunnelStep.START]?.detail == source }

        return FunnelReport(
            days = days,
            source = source,
            users = selected.size,
            steps = FunnelStep.entries.associateWith { step -> selected.values.count { events -> step in events } },
            sources = sources.sortedDescending(),
            providers = selected.values.mapNotNull { events -> events[FunnelStep.PROVIDER]?.detail }.groupingBy { it }.eachCount().sortedDescending(),
            errors = selected.values.mapNotNull { events -> events[FunnelStep.CONNECT_ERROR]?.detail }.groupingBy { it }.eachCount().sortedDescending(),
        )
    }

    /** Запустили бота больше часа назад (но не раньше days дней), а ящик так и не подключили. */
    suspend fun stuck(days: Int = STUCK_DAYS): List<StuckUser> {
        val olderThan = clock().minus(STUCK_MIN_AGE)
        return cohort(days).mapNotNull { (userId, events) ->
            val start = events[FunnelStep.START] ?: return@mapNotNull null
            if (FunnelStep.CONNECTED in events || start.at.isAfter(olderThan)) return@mapNotNull null
            StuckUser(
                userId = userId,
                startedAt = start.at,
                source = start.detail,
                lastStep = events.keys.filter { step -> step != FunnelStep.CONNECT_ERROR }.maxBy(FunnelStep::ordinal),
                error = events[FunnelStep.CONNECT_ERROR]?.detail,
            )
        }.sortedByDescending(StuckUser::startedAt)
    }

    // Пользователь -> его шаги, только для тех, кто впервые запустил бота за последние days дней
    private suspend fun cohort(days: Int): Map<Long, Map<FunnelStep, FunnelEvent>> {
        val since = clock().minus(Duration.ofDays(days.toLong()))
        return store.cohortEvents(since)
            .filter { event -> event.userId !in excludedIds }
            .mapNotNull { event -> FunnelStep.fromKey(event.step)?.let { step -> step to event } }
            .groupBy({ (_, event) -> event.userId }, { pair -> pair })
            .mapValues { (_, pairs) -> pairs.toMap() }
    }

    // Шаг start пишем только новым пользователям: тем, кто впервые появился у бота несколько минут назад
    private suspend fun recordStartIfNew(userId: Long, source: String) {
        val key = "$userId:${FunnelStep.START.key}"
        if (key in recorded) return
        safely(FunnelStep.START) {
            val first = firstSeen(userId) ?: return@safely
            if (Duration.between(first, clock()) > NEW_USER_WINDOW) {
                // Пользователь был и до воронки: в отчеты не попадает
                recorded.add(key)
                return@safely
            }
            store.insert(FunnelEvent(userId, FunnelStep.START.key, source, clock()))
            recorded.add(key)
        }
    }

    private suspend fun safely(step: FunnelStep?, block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Funnel step {} was not recorded: {}", step?.key ?: "delete", exception.javaClass.simpleName)
        }
    }

    private fun Map<String, Int>.sortedDescending(): Map<String, Int> =
        entries.sortedByDescending { entry -> entry.value }.associate { entry -> entry.key to entry.value }

    companion object {
        const val SOURCE_DIRECT = "direct"
        const val SOURCE_APP = "app"
        const val SOURCE_REFERRAL = "ref"
        const val SOURCE_INVITE = "invite"
        const val SOURCE_PREFIX = "src_"

        private const val DETAIL_MAX_LENGTH = 64
        private const val STUCK_DAYS = 14
        private val STUCK_MIN_AGE: Duration = Duration.ofHours(1)
        private val NEW_USER_WINDOW: Duration = Duration.ofMinutes(10)
        private val SOURCE_CHARS = Regex("[^a-z0-9_-]")

        /** Источник по параметру /start: src_habr -> habr, без параметра -> direct. */
        fun sourceOf(startArgument: String): String {
            val argument = startArgument.trim()
            return when {
                argument.isEmpty() -> SOURCE_DIRECT
                argument.startsWith(SOURCE_PREFIX) ->
                    argument.removePrefix(SOURCE_PREFIX).lowercase().replace(SOURCE_CHARS, "").take(32).ifEmpty { SOURCE_DIRECT }
                argument.startsWith(ReferralService.PREFIX) -> SOURCE_REFERRAL
                argument.startsWith(SubscriptionService.INVITE_PREFIX) -> SOURCE_INVITE
                else -> "other"
            }
        }
    }
}
