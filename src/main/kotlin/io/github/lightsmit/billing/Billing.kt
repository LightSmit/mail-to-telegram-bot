package io.github.lightsmit.billing

import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

/** Тариф пользователя. Сравнивать можно по порядку: FREE < PRO < BUSINESS. */
enum class Plan(val maxMailboxes: Int) {
    FREE(1),
    PRO(5),
    BUSINESS(15);

    /** Фильтры по отправителям и словам, полный просмотр писем с поиском, коды подтверждения. */
    val hasProFeatures: Boolean get() = this != FREE

    val title: String get() = name.lowercase().replaceFirstChar(Char::uppercase)

    companion object {
        fun parse(value: String): Plan? =
            entries.firstOrNull { plan -> plan.name.equals(value.trim(), ignoreCase = true) }
    }
}

/** Откуда у пользователя подписка. */
enum class SubscriptionSource {
    // Бесплатный пробный период для новых пользователей
    TRIAL,
    // Куплена за Telegram Stars
    STARS,
    // Выдана администратором (/grant)
    GRANT,
    // Получена по одноразовой ссылке (/invite)
    INVITE,
    // Награда за приглашенных друзей
    REFERRAL,
}

/**
 * Подписка пользователя (одна запись на человека).
 * Запись остается и после окончания срока: так видно, что пробный период уже был.
 */
data class Subscription(
    val userId: Long,
    val plan: Plan,
    // null - навсегда
    val expiresAt: Instant?,
    val source: SubscriptionSource,
    // Идентификатор оплаты Stars: нужен, чтобы отменить автопродление ежемесячной подписки
    val chargeId: String? = null,
    // Ежемесячная подписка Stars: Telegram сам списывает звезды каждые 30 дней
    val recurring: Boolean = false,
    // Когда отправлено напоминание о скором окончании (null - еще не отправляли)
    val remindedAt: Instant? = null,
    // Когда отправлено сообщение об окончании подписки
    val endedNotifiedAt: Instant? = null,
    val updatedAt: Instant,
) {
    val lifetime: Boolean get() = plan != Plan.FREE && expiresAt == null

    /**
     * До какого момента подписка действует. Ежемесячной подписке Stars даем час запаса:
     * Telegram списывает оплату в момент окончания, и сообщение о ней может прийти чуть позже.
     */
    val activeUntil: Instant?
        get() = expiresAt?.let { expires -> if (recurring) expires.plusSeconds(RENEWAL_GRACE_SECONDS) else expires }

    fun isActive(now: Instant): Boolean =
        plan != Plan.FREE && (activeUntil?.isAfter(now) ?: true)

    companion object {
        const val RENEWAL_GRACE_SECONDS = 3_600L
    }
}

/** Срок подписки: навсегда или период (дни, месяцы, годы). */
sealed interface GrantTerm {
    // Как срок записан в команде: "1m", "forever"... Так же хранится в приглашениях
    val spec: String

    fun addTo(start: Instant): Instant?

    data object Forever : GrantTerm {
        override val spec = "forever"
        override fun addTo(start: Instant): Instant? = null
    }

    data class Span(val period: Period, override val spec: String) : GrantTerm {
        override fun addTo(start: Instant): Instant =
            start.atZone(ZoneOffset.UTC).plus(period).toInstant()
    }

    companion object {
        private val pattern = Regex("""^(\d{1,3})\s*([dmyдмг])$""")

        /** "7d", "1m", "3m", "1y", "forever" (а также "7д", "1м", "1г", "навсегда"). */
        fun parse(raw: String): GrantTerm? {
            val value = raw.trim().lowercase()
            if (value in setOf("forever", "lifetime", "навсегда")) return Forever

            val match = pattern.matchEntire(value) ?: return null
            val amount = match.groupValues[1].toInt()
            if (amount <= 0) return null

            return when (match.groupValues[2]) {
                "d", "д" -> Span(Period.ofDays(amount), "${amount}d")
                "m", "м" -> Span(Period.ofMonths(amount), "${amount}m")
                else -> Span(Period.ofYears(amount), "${amount}y")
            }
        }
    }
}

/** Что можно купить за звезды. */
enum class Offer(val plan: Plan, val recurring: Boolean, val term: GrantTerm) {
    PRO_MONTH(Plan.PRO, recurring = true, term = GrantTerm.Span(Period.ofDays(30), "30d")),
    PRO_YEAR(Plan.PRO, recurring = false, term = GrantTerm.Span(Period.ofYears(1), "1y")),
    BUSINESS_MONTH(Plan.BUSINESS, recurring = true, term = GrantTerm.Span(Period.ofDays(30), "30d")),
    BUSINESS_YEAR(Plan.BUSINESS, recurring = false, term = GrantTerm.Span(Period.ofYears(1), "1y"));

    /** Метка счета: по ней понимаем, что купили, когда приходит оплата. */
    val payload: String get() = PAYLOAD_PREFIX + name

    companion object {
        private const val PAYLOAD_PREFIX = "sub1:"

        fun fromPayload(payload: String?): Offer? =
            payload
                ?.takeIf { value -> value.startsWith(PAYLOAD_PREFIX) }
                ?.removePrefix(PAYLOAD_PREFIX)
                ?.let { name -> entries.firstOrNull { offer -> offer.name == name } }

        fun parse(value: String?): Offer? =
            entries.firstOrNull { offer -> offer.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Настройки оплаты из .env. */
data class BillingConfig(
    // false - все пользуются ботом бесплатно (как до появления тарифов), оплата выключена
    val enabled: Boolean = false,
    val trialDays: Int = 7,
    val proMonthStars: Int = 100,
    val proYearStars: Int = 1_000,
    val businessMonthStars: Int = 300,
    val businessYearStars: Int = 3_000,
    // Business можно купить (до 15 ящиков и пересылка в групповой чат)
    val businessOnSale: Boolean = true,
    // Контакт для вопросов об оплате (/paysupport), например @username
    val supportContact: String? = null,
) {
    fun price(offer: Offer): Int = when (offer) {
        Offer.PRO_MONTH -> proMonthStars
        Offer.PRO_YEAR -> proYearStars
        Offer.BUSINESS_MONTH -> businessMonthStars
        Offer.BUSINESS_YEAR -> businessYearStars
    }

    fun onSale(offer: Offer): Boolean = enabled && (offer.plan != Plan.BUSINESS || businessOnSale)

    val offersOnSale: List<Offer> get() = Offer.entries.filter(::onSale)
}

/** Оплата звездами (для статистики, возвратов и защиты от повторной обработки). */
data class Payment(
    val userId: Long,
    val offer: Offer,
    val amount: Int,
    val chargeId: String,
    val recurring: Boolean,
    val createdAt: Instant,
    val refunded: Boolean = false,
)

/** Одноразовая ссылка-приглашение с подпиской. */
data class Invite(
    val code: String,
    val plan: Plan,
    val term: GrantTerm,
    val createdBy: Long,
    val createdAt: Instant,
    val usedBy: Long? = null,
    val usedAt: Instant? = null,
)

/** Что видит пользователь о своем тарифе. */
data class PlanStatus(
    val plan: Plan,
    val expiresAt: Instant?,
    val source: SubscriptionSource?,
    val recurring: Boolean,
    val lifetime: Boolean,
    val admin: Boolean,
    val billingEnabled: Boolean,
)

/** Сообщения пользователю о подписке, которые отправляет фоновая проверка. */
sealed interface SubscriptionNotice {
    val userId: Long

    data class Expiring(override val userId: Long, val plan: Plan, val expiresAt: Instant, val trial: Boolean) : SubscriptionNotice
    data class Ended(override val userId: Long, val plan: Plan, val trial: Boolean) : SubscriptionNotice
}
