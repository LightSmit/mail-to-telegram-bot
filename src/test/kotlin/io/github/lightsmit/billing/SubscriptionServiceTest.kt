package io.github.lightsmit.billing

import io.github.lightsmit.storage.SubscriptionStore
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Хранилище в памяти: логику тарифов проверяем без базы. */
private class MemoryStore : SubscriptionStore {
    val subscriptions = mutableMapOf<Long, Subscription>()
    val payments = mutableListOf<Payment>()
    val invites = mutableMapOf<String, Invite>()

    override suspend fun find(userId: Long) = subscriptions[userId]
    override suspend fun save(subscription: Subscription) { subscriptions[subscription.userId] = subscription }
    override suspend fun delete(userId: Long) { subscriptions.remove(userId) }
    override suspend fun findAll() = subscriptions.values.sortedBy { it.userId }
    override suspend fun recordPayment(payment: Payment): Boolean {
        if (payments.any { it.chargeId == payment.chargeId }) return false
        payments += payment
        return true
    }
    override suspend fun lastPayment(userId: Long) = payments.lastOrNull { it.userId == userId && !it.refunded }
    override suspend fun markRefunded(chargeId: String) {
        val index = payments.indexOfFirst { it.chargeId == chargeId }
        if (index >= 0) payments[index] = payments[index].copy(refunded = true)
    }
    override suspend fun countPayments(userId: Long) = payments.count { it.userId == userId }
    override suspend fun revenueSince(since: Instant?) =
        payments.filter { !it.refunded && (since == null || !it.createdAt.isBefore(since)) }.sumOf { it.amount.toLong() }
    override suspend fun createInvite(invite: Invite) { invites[invite.code] = invite }
    override suspend fun findInvite(code: String) = invites[code]
    override suspend fun redeemInvite(code: String, userId: Long, now: Instant): Invite? {
        val invite = invites[code] ?: return null
        if (invite.usedBy != null) return null
        return invite.copy(usedBy = userId, usedAt = now).also { invites[code] = it }
    }
}

class SubscriptionServiceTest {
    private val start = Instant.parse("2026-11-01T10:00:00Z")
    private var now = start
    private val store = MemoryStore()
    private val cancelled = mutableListOf<Pair<Long, String>>()
    private val refunded = mutableListOf<Pair<Long, String>>()

    private fun service(enabled: Boolean = true, trialDays: Int = 7) = SubscriptionService(
        store = store,
        config = BillingConfig(enabled = enabled, trialDays = trialDays),
        adminIds = setOf(ADMIN),
        cancelRenewal = { userId, chargeId -> cancelled += userId to chargeId },
        refundStars = { userId, chargeId -> refunded += userId to chargeId },
        clock = { now },
    )

    @Test
    fun `admins always have business`() = runBlocking {
        assertEquals(Plan.BUSINESS, service().plan(ADMIN))
        assertEquals(Plan.BUSINESS, service(enabled = false).plan(ADMIN))
    }

    @Test
    fun `without billing everyone gets at least pro and no trial is started`() = runBlocking {
        val service = service(enabled = false)
        assertEquals(Plan.PRO, service.plan(USER))
        assertNull(store.subscriptions[USER])
    }

    @Test
    fun `new user gets a pro trial that ends`() = runBlocking {
        val service = service()
        assertEquals(Plan.PRO, service.plan(USER))
        val trial = assertNotNull(store.subscriptions[USER])
        assertEquals(SubscriptionSource.TRIAL, trial.source)
        assertEquals(start.plus(Duration.ofDays(7)), trial.expiresAt)

        now = start.plus(Duration.ofDays(8))
        assertEquals(Plan.FREE, service.plan(USER))
        // Повторно пробный период не начинается
        assertEquals(SubscriptionSource.TRIAL, store.subscriptions[USER]?.source)
    }

    @Test
    fun `grant of the same plan extends, other plan restarts, forever stays`() = runBlocking {
        val service = service(trialDays = 0)
        val month = GrantTerm.parse("1m")!!
        val first = service.grant(USER, Plan.PRO, month, SubscriptionSource.GRANT)
        assertEquals(Instant.parse("2026-12-01T10:00:00Z"), first.expiresAt)

        val second = service.grant(USER, Plan.PRO, month, SubscriptionSource.GRANT)
        assertEquals(Instant.parse("2027-01-01T10:00:00Z"), second.expiresAt)

        val business = service.grant(USER, Plan.BUSINESS, GrantTerm.parse("7d")!!, SubscriptionSource.GRANT)
        assertEquals(start.plus(Duration.ofDays(7)), business.expiresAt)
        assertEquals(Plan.BUSINESS, service.plan(USER))

        val forever = service.grant(USER, Plan.BUSINESS, GrantTerm.Forever, SubscriptionSource.GRANT)
        assertNull(forever.expiresAt)
        val stillForever = service.grant(USER, Plan.BUSINESS, month, SubscriptionSource.GRANT)
        assertNull(stillForever.expiresAt)
    }

    @Test
    fun `revoke makes free and cancels stars renewal`() = runBlocking {
        val service = service(trialDays = 0)
        service.onPayment(USER, Offer.PRO_MONTH.payload, 100, "charge-1", start.plus(Duration.ofDays(30)), firstRecurring = true)
        assertEquals(Plan.PRO, service.plan(USER))

        val result = service.revoke(USER)
        assertTrue(result.hadSubscription)
        assertTrue(result.renewalCancelled)
        assertEquals(listOf(USER to "charge-1"), cancelled)
        assertEquals(Plan.FREE, service.plan(USER))
        assertFalse(service.revoke(USER).hadSubscription)
    }

    @Test
    fun `monthly payment keeps first charge id and renewals extend`() = runBlocking {
        val service = service()
        service.plan(USER) // пробный период
        val firstExpiry = start.plus(Duration.ofDays(30))
        val first = assertNotNull(
            service.onPayment(USER, Offer.PRO_MONTH.payload, 100, "charge-1", firstExpiry, firstRecurring = true),
        )
        assertEquals(firstExpiry, first.expiresAt)
        assertTrue(first.recurring)
        assertEquals("charge-1", first.chargeId)

        now = firstExpiry
        val renewed = assertNotNull(
            service.onPayment(USER, Offer.PRO_MONTH.payload, 100, "charge-2", firstExpiry.plus(Duration.ofDays(30)), firstRecurring = false),
        )
        assertEquals(firstExpiry.plus(Duration.ofDays(30)), renewed.expiresAt)
        assertEquals("charge-1", renewed.chargeId)

        // Тот же платеж повторно не продлевает
        val duplicate = service.onPayment(USER, Offer.PRO_MONTH.payload, 100, "charge-2", firstExpiry.plus(Duration.ofDays(60)), firstRecurring = false)
        assertEquals(firstExpiry.plus(Duration.ofDays(30)), duplicate?.expiresAt)
        assertEquals(200L, store.revenueSince(null))
    }

    @Test
    fun `yearly payment on top of monthly cancels renewal and adds a year`() = runBlocking {
        val service = service(trialDays = 0)
        val monthEnd = start.plus(Duration.ofDays(30))
        service.onPayment(USER, Offer.PRO_MONTH.payload, 100, "charge-1", monthEnd, firstRecurring = true)
        val yearly = assertNotNull(service.onPayment(USER, Offer.PRO_YEAR.payload, 1000, "charge-2", null, firstRecurring = false))
        assertEquals(listOf(USER to "charge-1"), cancelled)
        assertFalse(yearly.recurring)
        assertEquals(Instant.parse("2027-12-01T10:00:00Z"), yearly.expiresAt)
    }

    @Test
    fun `recurring plan has a grace hour for late renewal`() = runBlocking {
        val service = service(trialDays = 0)
        val monthEnd = start.plus(Duration.ofDays(30))
        service.onPayment(USER, Offer.PRO_MONTH.payload, 100, "charge-1", monthEnd, firstRecurring = true)
        now = monthEnd.plusSeconds(1_800)
        assertEquals(Plan.PRO, service.plan(USER))
        now = monthEnd.plusSeconds(7_200)
        assertEquals(Plan.FREE, service.plan(USER))
    }

    @Test
    fun `checkout validation`() = runBlocking {
        val service = service(trialDays = 0)
        assertEquals(Offer.PRO_MONTH, service.validateCheckout(USER, Offer.PRO_MONTH.payload, "XTR", 100))
        assertNull(service.validateCheckout(USER, Offer.PRO_MONTH.payload, "XTR", 99))
        assertNull(service.validateCheckout(USER, Offer.PRO_MONTH.payload, "USD", 100))
        assertNull(service.validateCheckout(USER, "something", "XTR", 100))
        assertEquals(Offer.BUSINESS_MONTH, service.validateCheckout(USER, Offer.BUSINESS_MONTH.payload, "XTR", 300))
        // Если Business снят с продажи, оплату не принимаем
        val noBusiness = SubscriptionService(store, BillingConfig(enabled = true, trialDays = 0, businessOnSale = false), setOf(ADMIN), clock = { now })
        assertNull(noBusiness.validateCheckout(USER, Offer.BUSINESS_MONTH.payload, "XTR", 300))
        assertNull(service.validateCheckout(ADMIN, Offer.PRO_MONTH.payload, "XTR", 100))

        service.grant(USER, Plan.PRO, GrantTerm.Forever, SubscriptionSource.INVITE)
        assertNull(service.validateCheckout(USER, Offer.PRO_YEAR.payload, "XTR", 1000))
    }

    @Test
    fun `second monthly subscription is rejected, renewal is accepted`() = runBlocking {
        val service = service(trialDays = 0)
        val monthEnd = start.plus(Duration.ofDays(30))
        service.onPayment(USER, Offer.PRO_MONTH.payload, 100, "charge-1", monthEnd, firstRecurring = true)
        assertNull(service.validateCheckout(USER, Offer.PRO_MONTH.payload, "XTR", 100))
        now = monthEnd.minusSeconds(60)
        assertEquals(Offer.PRO_MONTH, service.validateCheckout(USER, Offer.PRO_MONTH.payload, "XTR", 100))
    }

    @Test
    fun `monthly cannot be bought on top of a longer plan`() = runBlocking {
        val service = service(trialDays = 0)
        service.grant(USER, Plan.PRO, GrantTerm.parse("1y")!!, SubscriptionSource.GRANT)
        assertFalse(service.canBuy(USER, Offer.PRO_MONTH))
        assertTrue(service.canBuy(USER, Offer.PRO_YEAR))

        service.grant(OTHER, Plan.PRO, GrantTerm.parse("7d")!!, SubscriptionSource.GRANT)
        assertTrue(service.canBuy(OTHER, Offer.PRO_MONTH))
    }

    @Test
    fun `invite works once`() = runBlocking {
        val service = service(trialDays = 0)
        val invite = service.createInvite(Plan.BUSINESS, GrantTerm.Forever, ADMIN)
        assertTrue(invite.code.startsWith("vip_"))

        val activated = assertIs<SubscriptionService.InviteResult.Activated>(service.redeemInvite(invite.code, USER))
        assertEquals(Plan.BUSINESS, activated.subscription.plan)
        assertNull(activated.subscription.expiresAt)
        assertEquals(SubscriptionSource.INVITE, activated.subscription.source)

        assertEquals(SubscriptionService.InviteResult.AlreadyYours, service.redeemInvite(invite.code, USER))
        assertEquals(SubscriptionService.InviteResult.AlreadyUsed, service.redeemInvite(invite.code, OTHER))
        assertEquals(SubscriptionService.InviteResult.NotFound, service.redeemInvite("vip_missing", OTHER))
        assertEquals(Plan.FREE, service.plan(OTHER))
    }

    @Test
    fun `notices are sent once`() = runBlocking {
        val service = service()
        service.plan(USER) // пробный период на 7 дней
        assertTrue(service.collectNotices().isEmpty())

        now = start.plus(Duration.ofDays(6).plusHours(12))
        val expiring = service.collectNotices()
        assertIs<SubscriptionNotice.Expiring>(expiring.single())
        assertTrue(service.collectNotices().isEmpty())

        now = start.plus(Duration.ofDays(8))
        val ended = assertIs<SubscriptionNotice.Ended>(service.collectNotices().single())
        assertTrue(ended.trial)
        assertTrue(service.collectNotices().isEmpty())
    }

    @Test
    fun `refund returns stars and revokes`() = runBlocking {
        val service = service(trialDays = 0)
        service.onPayment(USER, Offer.PRO_YEAR.payload, 1000, "charge-9", null, firstRecurring = false)
        val result = assertIs<SubscriptionService.RefundResult.Refunded>(service.refundLast(USER))
        assertEquals(1000, result.payment.amount)
        assertEquals(listOf(USER to "charge-9"), refunded)
        assertEquals(Plan.FREE, service.plan(USER))
        assertEquals(0L, store.revenueSince(null))
        assertEquals(SubscriptionService.RefundResult.NoPayment, service.refundLast(USER))
    }

    @Test
    fun `deleting data keeps only an active paid subscription`() = runBlocking {
        val service = service()
        service.plan(USER) // пробный
        assertFalse(service.forgetUnlessActive(USER))
        assertNull(store.subscriptions[USER])

        service.grant(OTHER, Plan.PRO, GrantTerm.parse("1m")!!, SubscriptionSource.GRANT)
        assertTrue(service.forgetUnlessActive(OTHER))
        assertNotNull(store.subscriptions[OTHER])
    }

    private companion object {
        const val ADMIN = 1L
        const val USER = 100L
        const val OTHER = 200L
    }
}
