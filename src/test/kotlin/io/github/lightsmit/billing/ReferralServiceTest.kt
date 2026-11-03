package io.github.lightsmit.billing

import io.github.lightsmit.storage.Referral
import io.github.lightsmit.storage.ReferralStore
import io.github.lightsmit.storage.ReferrerSummary
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

private class MemoryReferrals : ReferralStore {
    val rows = mutableMapOf<Long, Referral>()
    override suspend fun find(invitedId: Long) = rows[invitedId]
    override suspend fun insert(referral: Referral): Boolean {
        if (referral.invitedId in rows) return false
        rows[referral.invitedId] = referral
        return true
    }
    override suspend fun markQualified(invitedId: Long, now: Instant) {
        rows[invitedId]?.takeIf { it.qualifiedAt == null }?.let { rows[invitedId] = it.copy(qualifiedAt = now) }
    }
    override suspend fun countInvited(referrerId: Long) = rows.values.count { it.referrerId == referrerId }
    override suspend fun countQualified(referrerId: Long) = rows.values.count { it.referrerId == referrerId && it.qualifiedAt != null }
    override suspend fun top(limit: Int) = rows.values.groupBy { it.referrerId }
        .map { (id, list) -> ReferrerSummary(id, list.size, list.count { it.qualifiedAt != null }) }
        .sortedByDescending { it.qualified }.take(limit)
}

class ReferralServiceTest {
    private val start = Instant.parse("2026-11-03T12:00:00Z")
    private var now = start
    private val firstSeen = mutableMapOf<Long, Instant>()
    private val store = MemoryReferrals()
    private val subscriptionStore = MemorySubscriptionsForReferrals()
    private val subscriptions = SubscriptionService(
        store = subscriptionStore,
        config = BillingConfig(enabled = true, trialDays = 0),
        adminIds = setOf(ADMIN),
        clock = { now },
    )
    private val service = ReferralService(
        store = store,
        subscriptions = subscriptions,
        goal = 5,
        reward = GrantTerm.parse("1m")!!,
        firstSeen = { id -> firstSeen[id] },
        clock = { now },
    )

    private suspend fun newFriend(id: Long): ReferralService.RegisterResult {
        firstSeen[id] = now
        return service.register(id, service.code(REFERRER))
    }

    @Test
    fun `codes round trip`() {
        assertEquals(REFERRER, service.parse(service.code(REFERRER)))
        assertNull(service.parse("ref_"))
        assertNull(service.parse("vip_abc"))
        assertNull(service.parse("ref_!!"))
    }

    @Test
    fun `only new users count, once, and not themselves`() = runBlocking {
        assertIs<ReferralService.RegisterResult.Registered>(newFriend(10))
        assertEquals(ReferralService.RegisterResult.Ignored, service.register(10, service.code(OTHER)))

        firstSeen[11] = start.minus(Duration.ofDays(3))
        assertEquals(ReferralService.RegisterResult.Ignored, service.register(11, service.code(REFERRER)))

        firstSeen[REFERRER] = now
        assertEquals(ReferralService.RegisterResult.Ignored, service.register(REFERRER, service.code(REFERRER)))
        assertEquals(1, service.stats(REFERRER).invited)
    }

    @Test
    fun `five friends with mailboxes give a month of pro, repeatedly`() = runBlocking {
        for (id in 1L..5L) newFriend(id)
        for (id in 1L..4L) {
            val progress = assertNotNull(service.onMailboxAdded(id))
            assertFalse(progress.rewarded)
        }
        // Повторное подключение ящика не считается второй раз
        assertNull(service.onMailboxAdded(1))
        // Пользователь не по ссылке - ничего
        assertNull(service.onMailboxAdded(99))

        val fifth = assertNotNull(service.onMailboxAdded(5))
        assertTrue(fifth.rewarded)
        assertEquals(Plan.PRO, subscriptions.plan(REFERRER))
        assertEquals(Instant.parse("2026-12-03T12:00:00Z"), fifth.subscription?.expiresAt)
        assertEquals(SubscriptionSource.REFERRAL, fifth.subscription?.source)

        for (id in 6L..10L) newFriend(id)
        for (id in 6L..9L) service.onMailboxAdded(id)
        val tenth = assertNotNull(service.onMailboxAdded(10))
        assertTrue(tenth.rewarded)
        assertEquals(Instant.parse("2027-01-03T12:00:00Z"), tenth.subscription?.expiresAt)

        val stats = service.stats(REFERRER)
        assertEquals(10, stats.qualified)
        assertEquals(2, stats.rewards)
        assertEquals(0, stats.towardsNext)
    }

    @Test
    fun `bonus extends a paid business plan and skips lifetime`() = runBlocking {
        subscriptions.grant(REFERRER, Plan.BUSINESS, GrantTerm.parse("1m")!!, SubscriptionSource.STARS)
        val extended = assertNotNull(subscriptions.addBonus(REFERRER, GrantTerm.parse("1m")!!))
        assertEquals(Plan.BUSINESS, extended.plan)
        assertEquals(Instant.parse("2027-01-03T12:00:00Z"), extended.expiresAt)
        assertEquals(SubscriptionSource.STARS, extended.source)

        subscriptions.grant(OTHER, Plan.PRO, GrantTerm.Forever, SubscriptionSource.INVITE)
        assertNull(subscriptions.addBonus(OTHER, GrantTerm.parse("1m")!!))
        assertNull(subscriptions.addBonus(ADMIN, GrantTerm.parse("1m")!!))
    }

    private companion object {
        const val ADMIN = 1L
        const val REFERRER = 500L
        const val OTHER = 600L
    }
}

private class MemorySubscriptionsForReferrals : io.github.lightsmit.storage.SubscriptionStore {
    val subscriptions = mutableMapOf<Long, Subscription>()
    override suspend fun find(userId: Long) = subscriptions[userId]
    override suspend fun save(subscription: Subscription) { subscriptions[subscription.userId] = subscription }
    override suspend fun delete(userId: Long) { subscriptions.remove(userId) }
    override suspend fun findAll() = subscriptions.values.toList()
    override suspend fun recordPayment(payment: Payment) = true
    override suspend fun lastPayment(userId: Long): Payment? = null
    override suspend fun markRefunded(chargeId: String) {}
    override suspend fun countPayments(userId: Long) = 0
    override suspend fun revenueSince(since: Instant?) = 0L
    override suspend fun createInvite(invite: Invite) {}
    override suspend fun findInvite(code: String): Invite? = null
    override suspend fun redeemInvite(code: String, userId: Long, now: Instant): Invite? = null
}
