package io.github.lightsmit.billing

import io.github.lightsmit.storage.SubscriptionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Тарифы и подписки: какой тариф у пользователя, оплата звездами, выдача и отзыв
 * подписок администратором, приглашения, пробный период и напоминания.
 *
 * Telegram-вызовы (отмена автопродления, возврат звезд) передаются снаружи,
 * чтобы логику можно было проверить без сети.
 */
class SubscriptionService(
    private val store: SubscriptionStore,
    val config: BillingConfig,
    private val adminIds: Set<Long>,
    // Отменить автопродление ежемесячной подписки Stars (editUserStarSubscription)
    private val cancelRenewal: suspend (userId: Long, chargeId: String) -> Unit = { _, _ -> },
    // Вернуть звезды за оплату (refundStarPayment)
    private val refundStars: suspend (userId: Long, chargeId: String) -> Unit = { _, _ -> },
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(SubscriptionService::class.java)

    // Тариф нужен при каждом письме: держим записи в памяти. NONE - записи в базе нет
    private val cache = ConcurrentHashMap<Long, Any>()
    private val writeLock = Mutex()
    private val random = SecureRandom()

    val enabled: Boolean get() = config.enabled

    fun isAdmin(userId: Long): Boolean = userId in adminIds

    /** Текущий тариф: админам - Business навсегда, при выключенной оплате - не ниже Pro. */
    suspend fun plan(userId: Long): Plan {
        if (isAdmin(userId)) return Plan.BUSINESS
        val now = clock()
        val active = subscriptionWithTrial(userId)?.takeIf { subscription -> subscription.isActive(now) }?.plan ?: Plan.FREE
        return if (config.enabled) active else maxOf(active, Plan.PRO)
    }

    suspend fun status(userId: Long): PlanStatus {
        val plan = plan(userId)
        if (isAdmin(userId)) {
            return PlanStatus(plan, expiresAt = null, source = null, recurring = false, lifetime = true, admin = true, billingEnabled = config.enabled)
        }
        val subscription = cached(userId)?.takeIf { value -> value.isActive(clock()) && value.plan == plan }
        return PlanStatus(
            plan = plan,
            expiresAt = subscription?.expiresAt,
            source = subscription?.source,
            recurring = subscription?.recurring == true,
            lifetime = subscription?.lifetime == true,
            admin = false,
            billingEnabled = config.enabled,
        )
    }

    /** Запись о подписке (в том числе закончившейся). */
    suspend fun subscription(userId: Long): Subscription? = cached(userId)

    /**
     * Выдать подписку (админ или приглашение). Тот же тариф продлевается от текущей даты окончания,
     * другой тариф начинается заново с сегодняшнего дня.
     */
    suspend fun grant(userId: Long, plan: Plan, term: GrantTerm, source: SubscriptionSource): Subscription {
        require(plan != Plan.FREE) { "Use revoke() to remove a subscription" }
        return writeLock.withLock {
            val now = clock()
            val current = cached(userId)
            val updated = extend(current, userId, plan, term, now).copy(source = source)
            persist(updated)
            logger.info("Subscription of user {} set to {} until {} ({})", userId, plan, updated.expiresAt ?: "forever", source)
            updated
        }
    }

    /** Отозвать любую подписку: тариф сразу становится Free, автопродление Stars отменяется. */
    suspend fun revoke(userId: Long): RevokeResult = writeLock.withLock {
        val now = clock()
        val current = cached(userId)
        if (current == null || (!current.isActive(now) && !current.recurring)) {
            return@withLock RevokeResult(hadSubscription = false, renewalCancelled = false, previousPlan = null)
        }

        val renewalCancelled = cancelRenewalSafely(current)
        persist(
            current.copy(
                plan = Plan.FREE,
                expiresAt = now,
                recurring = false,
                endedNotifiedAt = now,
                updatedAt = now,
            ),
        )
        logger.info("Subscription of user {} revoked (was {})", userId, current.plan)
        RevokeResult(hadSubscription = current.isActive(now), renewalCancelled = renewalCancelled, previousPlan = current.plan)
    }

    /**
     * Проверка перед оплатой (pre_checkout_query): тариф продается, цена совпадает
     * и покупка имеет смысл. null - оплату отклоняем.
     */
    suspend fun validateCheckout(userId: Long, payload: String?, currency: String?, amount: Int): Offer? {
        val offer = Offer.fromPayload(payload) ?: return null
        if (currency != STARS_CURRENCY) return null
        if (!config.onSale(offer)) return null
        if (amount != config.price(offer)) return null
        return offer.takeIf { canBuy(userId, offer) || isRenewal(userId, offer) }
    }

    // Автопродление ежемесячной подписки: на случай, если Telegram спросит подтверждение и для него
    private suspend fun isRenewal(userId: Long, offer: Offer): Boolean {
        val current = cached(userId) ?: return false
        val expiresAt = current.expiresAt ?: return false
        return offer.recurring && current.recurring && current.plan == offer.plan &&
            Duration.between(clock(), expiresAt) <= RENEWAL_WINDOW
    }

    /**
     * Можно ли купить: не навсегда ли уже подписка и не будет ли двух списаний сразу.
     * Ежемесячную подписку нельзя оформить поверх действующей ежемесячной или поверх более длинного срока.
     */
    suspend fun canBuy(userId: Long, offer: Offer): Boolean {
        if (isAdmin(userId)) return false
        val now = clock()
        val current = cached(userId)?.takeIf { subscription -> subscription.isActive(now) && subscription.source != SubscriptionSource.TRIAL }
            ?: return true
        if (current.lifetime) return false
        if (!offer.recurring) return true
        if (current.recurring) return false
        val monthLater = offer.term.addTo(now) ?: return false
        return current.expiresAt?.isAfter(monthLater) != true
    }

    /**
     * Пришла оплата (successful_payment). Для ежемесячной подписки Telegram присылает
     * такое же сообщение при каждом автопродлении.
     */
    suspend fun onPayment(
        userId: Long,
        payload: String?,
        amount: Int,
        chargeId: String,
        subscriptionExpiresAt: Instant?,
        firstRecurring: Boolean,
    ): Subscription? {
        val offer = Offer.fromPayload(payload)
        if (offer == null) {
            logger.warn("Payment {} from user {} has unknown payload", chargeId, userId)
            return null
        }

        return writeLock.withLock {
            val now = clock()
            val recorded = store.recordPayment(
                Payment(userId, offer, amount, chargeId, offer.recurring, now),
            )
            if (!recorded) {
                logger.info("Payment {} from user {} already processed", chargeId, userId)
                return@withLock cached(userId)
            }

            val current = cached(userId)

            // Купил годовой тариф поверх ежемесячного: ежемесячную подписку больше не продлеваем
            if (current != null && current.recurring && !offer.recurring) {
                cancelRenewalSafely(current)
            }

            val updated = if (offer.recurring && subscriptionExpiresAt != null) {
                val keepLonger = current != null && current.isActive(now) && current.plan == offer.plan &&
                    (current.expiresAt == null || current.expiresAt.isAfter(subscriptionExpiresAt))
                val base = current?.takeIf { keepLonger }
                Subscription(
                    userId = userId,
                    plan = offer.plan,
                    expiresAt = if (base != null) base.expiresAt else subscriptionExpiresAt,
                    source = SubscriptionSource.STARS,
                    // Для отмены автопродления нужен идентификатор первой оплаты подписки
                    chargeId = if (firstRecurring || current?.recurring != true) chargeId else current.chargeId,
                    recurring = true,
                    updatedAt = now,
                )
            } else {
                extend(current?.takeIf { value -> !value.recurring || !offer.recurring }, userId, offer.plan, offer.term, now)
                    .copy(source = SubscriptionSource.STARS, chargeId = chargeId, recurring = false)
            }

            persist(updated)
            logger.info("Payment {} from user {}: {} for {} stars, active until {}", chargeId, userId, offer, amount, updated.expiresAt ?: "forever")
            updated
        }
    }

    /** Вернуть звезды за последнюю оплату и отозвать подписку. */
    suspend fun refundLast(userId: Long): RefundResult {
        val payment = store.lastPayment(userId) ?: return RefundResult.NoPayment
        return try {
            refundStars(userId, payment.chargeId)
            store.markRefunded(payment.chargeId)
            revoke(userId)
            logger.info("Payment {} of user {} refunded ({} stars)", payment.chargeId, userId, payment.amount)
            RefundResult.Refunded(payment)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Refund of payment {} for user {} failed: {}", payment.chargeId, userId, exception.message)
            RefundResult.Failed(payment, exception.message ?: exception.javaClass.simpleName)
        }
    }

    /** Новая одноразовая ссылка: возвращает код для ?start=. */
    suspend fun createInvite(plan: Plan, term: GrantTerm, adminId: Long): Invite {
        require(plan != Plan.FREE) { "Invite must grant a paid plan" }
        val invite = Invite(
            code = INVITE_PREFIX + randomCode(),
            plan = plan,
            term = term,
            createdBy = adminId,
            createdAt = clock(),
        )
        store.createInvite(invite)
        logger.info("Invite for {} ({}) created by {}", plan, term.spec, adminId)
        return invite
    }

    /** Активировать приглашение из ссылки t.me/бот?start=vip_... */
    suspend fun redeemInvite(code: String, userId: Long): InviteResult {
        if (!code.startsWith(INVITE_PREFIX) || code.length > 64) return InviteResult.NotFound
        val invite = store.redeemInvite(code, userId, clock())
        if (invite == null) {
            val existing = store.findInvite(code) ?: return InviteResult.NotFound
            return if (existing.usedBy == userId) InviteResult.AlreadyYours else InviteResult.AlreadyUsed
        }
        return InviteResult.Activated(grant(userId, invite.plan, invite.term, SubscriptionSource.INVITE))
    }

    /** Действующие подписки (без админов из .env): для /subs. */
    suspend fun activeSubscriptions(): List<Subscription> {
        val now = clock()
        return store.findAll().filter { subscription -> subscription.isActive(now) }
    }

    suspend fun stats(): BillingStats {
        val now = clock()
        val active = activeSubscriptions()
        return BillingStats(
            pro = active.count { subscription -> subscription.plan == Plan.PRO && subscription.source != SubscriptionSource.TRIAL },
            business = active.count { subscription -> subscription.plan == Plan.BUSINESS },
            trials = active.count { subscription -> subscription.source == SubscriptionSource.TRIAL },
            lifetime = active.count { subscription -> subscription.lifetime },
            revenue30Days = store.revenueSince(now.minus(Duration.ofDays(30))),
            revenueTotal = store.revenueSince(null),
        )
    }

    /**
     * Удаление данных пользователя: запись о подписке удаляем, если в ней нет оплаченного
     * или выданного срока. Действующую подписку сохраняем, чтобы человек не потерял оплаченное.
     * true - запись сохранена.
     */
    suspend fun forgetUnlessActive(userId: Long): Boolean = writeLock.withLock {
        val current = cached(userId) ?: return@withLock false
        val keep = current.isActive(clock()) && current.source != SubscriptionSource.TRIAL
        if (!keep) {
            store.delete(userId)
            cache.remove(userId)
        }
        keep
    }

    /** Напоминания о скором окончании и сообщения об окончании подписки. */
    suspend fun collectNotices(): List<SubscriptionNotice> = writeLock.withLock {
        val now = clock()
        val notices = mutableListOf<SubscriptionNotice>()

        for (subscription in store.findAll()) {
            val expiresAt = subscription.expiresAt ?: continue
            if (subscription.plan == Plan.FREE || isAdmin(subscription.userId)) continue
            val trial = subscription.source == SubscriptionSource.TRIAL

            if (subscription.isActive(now)) {
                // Ежемесячную подписку Telegram продлевает сам: напоминать не о чем
                if (subscription.recurring || subscription.remindedAt != null) continue
                val window = if (trial) TRIAL_REMIND_BEFORE else REMIND_BEFORE
                if (Duration.between(now, expiresAt) <= window) {
                    persist(subscription.copy(remindedAt = now))
                    notices += SubscriptionNotice.Expiring(subscription.userId, subscription.plan, expiresAt, trial)
                }
            } else if (subscription.endedNotifiedAt == null) {
                persist(subscription.copy(endedNotifiedAt = now, recurring = false))
                notices += SubscriptionNotice.Ended(subscription.userId, subscription.plan, trial)
            }
        }
        notices
    }

    /** Фоновая проверка подписок раз в 30 минут. */
    suspend fun runSchedule(send: suspend (SubscriptionNotice) -> Unit) {
        while (currentCoroutineContext().isActive) {
            if (config.enabled) {
                try {
                    collectNotices().forEach { notice ->
                        try {
                            send(notice)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            logger.warn("Failed to send subscription notice to {}: {}", notice.userId, exception.javaClass.simpleName)
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    logger.warn("Subscription check failed: {}", exception.javaClass.simpleName)
                }
            }
            delay(SCHEDULE_INTERVAL_MILLIS)
        }
    }

    // Новый срок: тот же действующий тариф продлеваем от его даты окончания, иначе считаем от сейчас
    private fun extend(current: Subscription?, userId: Long, plan: Plan, term: GrantTerm, now: Instant): Subscription {
        val samePlanActive = current != null && current.isActive(now) && current.plan == plan
        val expiresAt = when {
            term is GrantTerm.Forever -> null
            samePlanActive && current?.expiresAt == null -> null
            samePlanActive -> term.addTo(maxOf(current?.expiresAt ?: now, now))
            else -> term.addTo(now)
        }
        return Subscription(
            userId = userId,
            plan = plan,
            expiresAt = expiresAt,
            source = current?.source ?: SubscriptionSource.GRANT,
            chargeId = current?.chargeId,
            recurring = current?.recurring == true && samePlanActive,
            updatedAt = now,
        )
    }

    // Новым пользователям при включенной оплате - пробный Pro на TRIAL_DAYS дней
    private suspend fun subscriptionWithTrial(userId: Long): Subscription? {
        val existing = cached(userId)
        if (existing != null || !config.enabled || config.trialDays <= 0) return existing

        return writeLock.withLock {
            cached(userId) ?: run {
                val now = clock()
                val trial = Subscription(
                    userId = userId,
                    plan = Plan.PRO,
                    expiresAt = now.plus(Duration.ofDays(config.trialDays.toLong())),
                    source = SubscriptionSource.TRIAL,
                    updatedAt = now,
                )
                persist(trial)
                logger.info("Trial started for user {} until {}", userId, trial.expiresAt)
                trial
            }
        }
    }

    private suspend fun cached(userId: Long): Subscription? {
        val value = cache[userId] ?: (store.find(userId) ?: NONE).also { loaded -> cache[userId] = loaded }
        return value as? Subscription
    }

    private suspend fun persist(subscription: Subscription) {
        store.save(subscription)
        cache[subscription.userId] = subscription
    }

    private suspend fun cancelRenewalSafely(subscription: Subscription): Boolean {
        val chargeId = subscription.chargeId
        if (!subscription.recurring || chargeId == null) return false
        return try {
            cancelRenewal(subscription.userId, chargeId)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to cancel renewal for user {}: {}", subscription.userId, exception.message)
            false
        }
    }

    private fun randomCode(): String = buildString {
        repeat(INVITE_CODE_LENGTH) { append(INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)]) }
    }

    data class RevokeResult(val hadSubscription: Boolean, val renewalCancelled: Boolean, val previousPlan: Plan?)

    sealed interface RefundResult {
        data object NoPayment : RefundResult
        data class Refunded(val payment: Payment) : RefundResult
        data class Failed(val payment: Payment, val reason: String) : RefundResult
    }

    sealed interface InviteResult {
        data class Activated(val subscription: Subscription) : InviteResult
        data object NotFound : InviteResult
        data object AlreadyUsed : InviteResult
        data object AlreadyYours : InviteResult
    }

    data class BillingStats(
        val pro: Int,
        val business: Int,
        val trials: Int,
        val lifetime: Int,
        val revenue30Days: Long,
        val revenueTotal: Long,
    )

    companion object {
        const val STARS_CURRENCY = "XTR"
        const val INVITE_PREFIX = "vip_"
        // Ежемесячная подписка Stars: Telegram принимает только период 30 дней
        const val SUBSCRIPTION_PERIOD_SECONDS = 2_592_000

        private val NONE = Any()
        private val REMIND_BEFORE: Duration = Duration.ofDays(3)
        private val TRIAL_REMIND_BEFORE: Duration = Duration.ofDays(1)
        private val RENEWAL_WINDOW: Duration = Duration.ofDays(1)
        private const val SCHEDULE_INTERVAL_MILLIS = 30 * 60 * 1000L
        private const val INVITE_CODE_LENGTH = 12
        private const val INVITE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
