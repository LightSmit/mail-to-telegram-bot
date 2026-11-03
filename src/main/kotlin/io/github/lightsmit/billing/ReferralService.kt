package io.github.lightsmit.billing

import io.github.lightsmit.storage.Referral
import io.github.lightsmit.storage.ReferralStore
import io.github.lightsmit.storage.ReferrerSummary
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Приглашение друзей: у каждого своя ссылка t.me/бот?start=ref_код. Друг засчитывается,
 * если он новый пользователь (впервые открыл бота по ссылке) и подключил хотя бы один ящик.
 * За каждые [goal] засчитанных друзей пригласивший получает [reward] Pro.
 */
class ReferralService(
    private val store: ReferralStore,
    private val subscriptions: SubscriptionService,
    // Сколько друзей нужно для награды (0 - приглашения выключены)
    val goal: Int,
    private val reward: GrantTerm,
    // Когда пользователь впервые появился в боте: по ссылке засчитываются только новые
    private val firstSeen: suspend (Long) -> Instant?,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(ReferralService::class.java)

    val enabled: Boolean get() = goal > 0 && subscriptions.enabled

    /** Код ссылки: ID пользователя в 36-ричной записи. */
    fun code(userId: Long): String = PREFIX + userId.toString(36)

    fun parse(code: String): Long? =
        code.takeIf { value -> value.startsWith(PREFIX) && value.length <= 24 }
            ?.removePrefix(PREFIX)
            ?.toLongOrNull(36)
            ?.takeIf { id -> id > 0 }

    /** Новый пользователь открыл бота по ссылке друга. */
    suspend fun register(invitedId: Long, code: String): RegisterResult {
        if (!enabled) return RegisterResult.Ignored
        val referrerId = parse(code) ?: return RegisterResult.Ignored
        if (referrerId == invitedId) return RegisterResult.Ignored
        if (store.find(invitedId) != null) return RegisterResult.Ignored

        // Только новые: кто уже пользовался ботом, по чужой ссылке не засчитывается
        val seen = firstSeen(invitedId)
        if (seen != null && Duration.between(seen, clock()) > NEW_USER_WINDOW) return RegisterResult.Ignored

        if (!store.insert(Referral(invitedId, referrerId, clock()))) return RegisterResult.Ignored
        logger.info("User {} came by the invite link of {}", invitedId, referrerId)
        return RegisterResult.Registered(referrerId)
    }

    /** Пользователь подключил ящик: если его пригласили, друг засчитывается пригласившему. */
    suspend fun onMailboxAdded(invitedId: Long): Progress? {
        if (!enabled) return null
        val referral = store.find(invitedId) ?: return null
        if (referral.qualifiedAt != null) return null

        store.markQualified(invitedId, clock())
        val qualified = store.countQualified(referral.referrerId)
        val rewarded = qualified % goal == 0
        val subscription = if (rewarded) subscriptions.addBonus(referral.referrerId, reward) else null
        logger.info(
            "Referral of {} by {} qualified: {} friend(s){}",
            invitedId, referral.referrerId, qualified, if (rewarded) ", reward granted" else "",
        )
        return Progress(
            referrerId = referral.referrerId,
            qualified = qualified,
            goal = goal,
            rewarded = rewarded,
            subscription = subscription,
        )
    }

    suspend fun stats(userId: Long): Stats {
        val qualified = store.countQualified(userId)
        return Stats(
            invited = store.countInvited(userId),
            qualified = qualified,
            goal = goal,
            rewards = if (goal > 0) qualified / goal else 0,
            towardsNext = if (goal > 0) qualified % goal else 0,
        )
    }

    suspend fun top(limit: Int): List<ReferrerSummary> = store.top(limit)

    sealed interface RegisterResult {
        data class Registered(val referrerId: Long) : RegisterResult
        data object Ignored : RegisterResult
    }

    data class Progress(
        val referrerId: Long,
        val qualified: Int,
        val goal: Int,
        val rewarded: Boolean,
        // null при награде - подписка уже навсегда, продлевать нечего
        val subscription: Subscription?,
    )

    data class Stats(val invited: Int, val qualified: Int, val goal: Int, val rewards: Int, val towardsNext: Int)

    companion object {
        const val PREFIX = "ref_"
        private val NEW_USER_WINDOW: Duration = Duration.ofHours(1)
    }
}
