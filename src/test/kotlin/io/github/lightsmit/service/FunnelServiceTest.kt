package io.github.lightsmit.service

import io.github.lightsmit.storage.FunnelEvent
import io.github.lightsmit.storage.FunnelStore
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunnelServiceTest {
    private val start = Instant.parse("2026-11-14T10:00:00Z")
    private var now = start
    private val firstSeen = mutableMapOf<Long, Instant>()
    private val store = MemoryFunnelStore()

    private fun service() = FunnelService(
        store = store,
        firstSeen = { userId -> firstSeen[userId] },
        excludedIds = setOf(ADMIN),
        clock = { now },
    )

    @Test
    fun `source is taken from the start parameter`() {
        assertEquals("habr", FunnelService.sourceOf("src_habr"))
        assertEquals("vc", FunnelService.sourceOf("src_VC!"))
        assertEquals("direct", FunnelService.sourceOf(""))
        assertEquals("ref", FunnelService.sourceOf("ref_abc123"))
        assertEquals("invite", FunnelService.sourceOf("vip_code"))
        assertEquals("other", FunnelService.sourceOf("something"))
    }

    @Test
    fun `only new users get into the funnel and each step counts once`() = runBlocking {
        val service = service()
        firstSeen[NEW] = now
        firstSeen[OLD] = now.minus(Duration.ofDays(30))

        service.onStart(NEW, "src_habr")
        service.onStart(OLD, "src_habr")
        service.onAppOpen(NEW)
        service.onAppOpen(NEW)
        service.record(NEW, FunnelStep.PROVIDER, "gmail")
        service.record(NEW, FunnelStep.PROVIDER, "mailru")
        service.record(NEW, FunnelStep.SUBMIT)
        service.record(NEW, FunnelStep.CONNECT_ERROR, "auth")
        service.record(NEW, FunnelStep.CONNECTED)

        val report = service.report(days = 30)
        assertEquals(1, report.users)
        assertEquals(1, report.steps[FunnelStep.APP_OPEN])
        assertEquals(1, report.steps[FunnelStep.CONNECTED])
        assertEquals(0, report.steps[FunnelStep.PAID])
        assertEquals(mapOf("habr" to 1), report.sources)
        assertEquals(mapOf("gmail" to 1), report.providers)
        assertEquals(mapOf("auth" to 1), report.errors)
        assertEquals(0, service.report(days = 30, source = "vc").users)
    }

    @Test
    fun `app opened without start counts as source app, admins are hidden`() = runBlocking {
        val service = service()
        firstSeen[NEW] = now
        firstSeen[ADMIN] = now
        service.onAppOpen(NEW)
        service.onStart(ADMIN, "")

        val report = service.report(days = 7)
        assertEquals(1, report.users)
        assertEquals(mapOf("app" to 1), report.sources)
    }

    @Test
    fun `stuck lists users without a mailbox after an hour`() = runBlocking {
        val service = service()
        firstSeen[NEW] = now
        firstSeen[OTHER] = now
        service.onStart(NEW, "src_vc")
        service.onAppOpen(NEW)
        service.record(NEW, FunnelStep.SUBMIT)
        service.record(NEW, FunnelStep.CONNECT_ERROR, "auth")
        service.onStart(OTHER, "")
        service.record(OTHER, FunnelStep.CONNECTED)

        assertTrue(service.stuck().isEmpty(), "меньше часа - еще не застрял")
        now = now.plus(Duration.ofHours(2))
        val stuck = service.stuck()
        assertEquals(listOf(NEW), stuck.map(StuckUser::userId))
        assertEquals(FunnelStep.SUBMIT, stuck.single().lastStep)
        assertEquals("auth", stuck.single().error)
        assertEquals("vc", stuck.single().source)

        service.forget(NEW)
        assertTrue(service.stuck().isEmpty())
    }

    private class MemoryFunnelStore : FunnelStore {
        val events = mutableListOf<FunnelEvent>()

        override suspend fun insert(event: FunnelEvent): Boolean {
            if (events.any { it.userId == event.userId && it.step == event.step }) return false
            events += event
            return true
        }

        override suspend fun cohortEvents(since: Instant): List<FunnelEvent> {
            val cohort = events.filter { it.step == "start" && !it.at.isBefore(since) }.map { it.userId }.toSet()
            return events.filter { it.userId in cohort }
        }

        override suspend fun deleteUser(userId: Long) {
            events.removeAll { it.userId == userId }
        }
    }

    private companion object {
        const val NEW = 1L
        const val OLD = 2L
        const val OTHER = 3L
        const val ADMIN = 99L
    }
}
