package io.github.lightsmit.service

import io.github.lightsmit.billing.GrantTerm
import io.github.lightsmit.billing.Plan
import io.github.lightsmit.billing.Subscription
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.billing.SubscriptionSource
import io.github.lightsmit.storage.BotUserInfo
import io.github.lightsmit.storage.BotUserRepository
import io.github.lightsmit.storage.FunnelEvent
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxOperation
import io.github.lightsmit.storage.MailOutboxStatus
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

// --- Данные админ-панели (уходят в приложение как JSON). Время - миллисекунды UTC ---

@Serializable
data class AdminEventDto(
    val userId: Long,
    val name: String,
    val step: String,
    val title: String,
    val detail: String? = null,
    val at: Long,
)

@Serializable
data class AdminOverviewDto(
    val days: Int,
    val usersTotal: Long,
    val usersNew: Long,
    val usersActiveWeek: Long,
    val usersBlocked: Long,
    // Воронка новых за период: сколько пришло и сколько подключили ящик
    val cohort: Int,
    val cohortConnected: Int,
    val paid: Int,
    val trials: Int,
    val revenue30: Long,
    val revenueTotal: Long,
    val billingEnabled: Boolean,
    val mailboxes: Int,
    val mailboxesOk: Int,
    val mailboxesError: Int,
    val mailboxesConnecting: Int,
    val sentDay: Long,
    val sentWeek: Long,
    val deadWeek: Long,
    val queued: Long,
    val stuck: Int,
    val stuckWithError: Int,
    val uptimeMillis: Long,
    val lastBackupAt: Long? = null,
    val events: List<AdminEventDto>,
)

@Serializable
data class AdminUserItemDto(
    val id: Long,
    val name: String,
    val username: String? = null,
    val plan: String,
    val trial: Boolean,
    val mailboxes: Int,
    val mailboxErrors: Int,
    val lastSeenAt: Long,
    val firstSeenAt: Long? = null,
    val blocked: Boolean,
    val admin: Boolean,
)

@Serializable
data class AdminUsersDto(
    val total: Int,
    val counts: Map<String, Int>,
    val items: List<AdminUserItemDto>,
)

@Serializable
data class AdminMailboxDto(
    val id: Long,
    val email: String,
    val host: String,
    val forward: Boolean,
    val status: String,
    val problem: String? = null,
    val paused: Boolean,
)

@Serializable
data class AdminPaymentDto(
    val amount: Int,
    val offer: String,
    val at: Long,
)

@Serializable
data class AdminUserCardDto(
    val id: Long,
    val name: String,
    val username: String? = null,
    val firstSeenAt: Long? = null,
    val lastSeenAt: Long? = null,
    val blocked: Boolean,
    val admin: Boolean,
    val language: String,
    val plan: String,
    val source: String? = null,
    val expiresAt: Long? = null,
    val lifetime: Boolean,
    val recurring: Boolean,
    val renewalCancelled: Boolean,
    // Есть запись о пробном периоде (ее можно сбросить)
    val trialRecord: Boolean,
    val lastPayment: AdminPaymentDto? = null,
    val startSource: String? = null,
    val mailboxes: List<AdminMailboxDto>,
    val funnel: List<AdminEventDto>,
)

/** Результат действия администратора: текст для уведомления и обновленная карточка. */
@Serializable
data class AdminActionDto(
    val message: String,
    val card: AdminUserCardDto? = null,
)

/**
 * Данные и действия админ-панели в приложении. Только чтение из существующих хранилищ
 * и те же операции, что у команд /grant, /revoke, /refund.
 */
class AdminPanelService(
    private val adminIds: Set<Long>,
    private val botUsers: BotUserRepository,
    private val accounts: MailAccountRepository,
    private val outbox: MailNotificationOutboxRepository,
    private val healthMonitor: MailboxHealthMonitor,
    private val backupService: BackupService,
    private val telegramClient: TelegramClient,
    private val subscriptions: SubscriptionService?,
    private val funnel: FunnelService?,
    private val deleteUserData: suspend (Long) -> Int,
    private val startedAt: Instant = Instant.now(),
) {
    private val logger = LoggerFactory.getLogger(AdminPanelService::class.java)

    fun isAdmin(userId: Long): Boolean = userId in adminIds

    suspend fun overview(days: Int = OVERVIEW_DAYS): AdminOverviewDto {
        val now = Instant.now()
        val since = now.minus(Duration.ofDays(days.toLong()))
        val day = now.minus(Duration.ofDays(1))
        val week = now.minus(Duration.ofDays(7))

        val allAccounts = accounts.findAll()
        val statuses = allAccounts.groupingBy { account -> healthMonitor.health(account.id).status }.eachCount()
        val report = funnel?.report(days)
        val stuck = funnel?.stuck(days).orEmpty()
        val billing = subscriptions?.stats()
        val events = funnel?.recentEvents(EVENTS_LIMIT).orEmpty()

        return AdminOverviewDto(
            days = days,
            usersTotal = botUsers.count(),
            usersNew = botUsers.countFirstSeenSince(since),
            usersActiveWeek = botUsers.countSeenSince(week),
            usersBlocked = botUsers.countBlocked(),
            cohort = report?.users ?: 0,
            cohortConnected = report?.steps?.get(FunnelStep.CONNECTED) ?: 0,
            paid = (billing?.pro ?: 0) + (billing?.business ?: 0),
            trials = billing?.trials ?: 0,
            revenue30 = billing?.revenue30Days ?: 0,
            revenueTotal = billing?.revenueTotal ?: 0,
            billingEnabled = subscriptions?.enabled == true,
            mailboxes = allAccounts.size,
            mailboxesOk = statuses[MailboxStatus.OK] ?: 0,
            mailboxesError = statuses[MailboxStatus.ERROR] ?: 0,
            mailboxesConnecting = statuses[MailboxStatus.CONNECTING] ?: 0,
            sentDay = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, day),
            sentWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, week),
            deadWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.DEAD, week),
            queued = outbox.countByStatus(MailOutboxStatus.PENDING) + outbox.countByStatus(MailOutboxStatus.RETRY),
            stuck = stuck.size,
            stuckWithError = stuck.count { user -> user.error != null },
            uptimeMillis = Duration.between(startedAt, now).toMillis(),
            lastBackupAt = backupService.lastBackup?.createdAt?.toEpochMilli(),
            events = eventsDto(events),
        )
    }

    /**
     * Пользователи для списка: поиск по ID, @username и имени, фильтр.
     * filter: all, paid, free, nobox, errors.
     */
    suspend fun users(query: String?, filter: String?, limit: Int): AdminUsersDto {
        val now = Instant.now()
        val subs = subscriptions?.allSubscriptions().orEmpty()
        val byOwner = accounts.findAll().groupBy { account -> account.ownerChatId }

        val all = botUsers.all().map { user ->
            val owned = byOwner[user.userId].orEmpty()
            val subscription = subs[user.userId]?.takeIf { value -> value.isActive(now) }
            AdminUserItemDto(
                id = user.userId,
                name = displayName(user),
                username = user.username,
                plan = planOf(user.userId, subscription).name,
                trial = subscription?.source == SubscriptionSource.TRIAL,
                mailboxes = owned.size,
                mailboxErrors = owned.count { account -> healthMonitor.health(account.id).status == MailboxStatus.ERROR },
                lastSeenAt = user.lastSeenAt.toEpochMilli(),
                firstSeenAt = user.firstSeenAt?.toEpochMilli(),
                blocked = user.blocked,
                admin = isAdmin(user.userId),
            )
        }

        val tests: Map<String, (AdminUserItemDto) -> Boolean> = mapOf(
            "all" to { _ -> true },
            "paid" to { item -> item.plan != Plan.FREE.name && !item.trial && !item.admin },
            "free" to { item -> item.plan == Plan.FREE.name },
            "nobox" to { item -> item.mailboxes == 0 },
            "errors" to { item -> item.mailboxErrors > 0 },
        )
        val needle = query?.trim()?.removePrefix("@")?.lowercase()?.takeIf(String::isNotEmpty)
        val matching = if (needle == null) {
            all
        } else {
            all.filter { item ->
                item.id.toString().contains(needle) ||
                    item.username?.lowercase()?.contains(needle) == true ||
                    item.name.lowercase().contains(needle)
            }
        }
        val test = tests[filter] ?: tests.getValue("all")
        val filtered = matching.filter(test)
        return AdminUsersDto(
            total = filtered.size,
            counts = tests.mapValues { (_, predicate) -> matching.count(predicate) },
            items = filtered.take(limit.coerceIn(1, MAX_USERS)),
        )
    }

    suspend fun card(userId: Long): AdminUserCardDto? {
        val user = botUsers.find(userId)
        val owned = accounts.findAllByOwnerId(userId)
        if (user == null && owned.isEmpty()) return null

        val now = Instant.now()
        val stored = subscriptions?.subscription(userId)
        val active = stored?.takeIf { value -> value.isActive(now) }
        val events = funnel?.events(userId).orEmpty()
        val lang = botUsers.language(userId)
        val payment = subscriptions?.lastPayment(userId)

        return AdminUserCardDto(
            id = userId,
            name = user?.let(::displayName) ?: userId.toString(),
            username = user?.username,
            firstSeenAt = user?.firstSeenAt?.toEpochMilli(),
            lastSeenAt = user?.lastSeenAt?.toEpochMilli(),
            blocked = user?.blocked == true,
            admin = isAdmin(userId),
            language = lang.name.lowercase(),
            plan = planOf(userId, active).name,
            source = active?.source?.name,
            expiresAt = active?.expiresAt?.toEpochMilli(),
            lifetime = active?.lifetime == true || isAdmin(userId),
            recurring = active?.recurring == true,
            renewalCancelled = active?.renewalCancelled == true,
            trialRecord = stored?.source == SubscriptionSource.TRIAL,
            lastPayment = payment?.let { value ->
                AdminPaymentDto(amount = value.amount, offer = value.offer.name, at = value.createdAt.toEpochMilli())
            },
            startSource = events.firstOrNull { event -> event.step == FunnelStep.START.key }?.detail,
            mailboxes = owned.map { account ->
                val health = healthMonitor.health(account.id)
                AdminMailboxDto(
                    id = account.id,
                    email = account.username,
                    host = account.host,
                    forward = account.isForward,
                    status = health.status.name,
                    problem = health.problem?.text(lang.texts),
                    paused = account.filters.isPausedAt(),
                )
            },
            funnel = eventsDto(events),
        )
    }

    /** Выдать или продлить подписку; человек получает сообщение от бота. */
    suspend fun grant(userId: Long, planName: String, termSpec: String): AdminActionDto {
        val service = subscriptions ?: return AdminActionDto(BILLING_OFF)
        val plan = Plan.parse(planName)?.takeIf { value -> value != Plan.FREE }
            ?: return AdminActionDto("Неизвестный тариф: $planName")
        val term = GrantTerm.parse(termSpec) ?: return AdminActionDto("Не понял срок: $termSpec")
        if (isAdmin(userId)) return AdminActionDto("У админа всегда Business навсегда")

        val subscription = service.grant(userId, plan, term, SubscriptionSource.GRANT)
        notifyGranted(subscription)
        logger.info("Admin panel: {} granted to {} ({})", plan, userId, term.spec)
        return AdminActionDto("Выдано. Пользователь получил сообщение от бота.", card(userId))
    }

    /** Отозвать подписку; refund - еще и вернуть звезды за последнюю оплату. */
    suspend fun revoke(userId: Long, refund: Boolean): AdminActionDto {
        val service = subscriptions ?: return AdminActionDto(BILLING_OFF)
        val message = if (refund) {
            when (val result = service.refundLast(userId)) {
                SubscriptionService.RefundResult.NoPayment -> {
                    service.revoke(userId)
                    "Оплат для возврата нет. Подписка отозвана."
                }
                is SubscriptionService.RefundResult.Refunded -> "Подписка отозвана, ${result.payment.amount}⭐ возвращены."
                is SubscriptionService.RefundResult.Failed -> "Telegram не принял возврат ${result.payment.amount}⭐: ${result.reason}"
            }
        } else {
            val result = service.revoke(userId)
            when {
                !result.hadSubscription -> "Действующей подписки не было."
                result.renewalCancelled -> "Подписка отозвана, автопродление отменено."
                else -> "Подписка отозвана."
            }
        }
        logger.info("Admin panel: subscription of {} revoked (refund={})", userId, refund)
        return AdminActionDto(message, card(userId))
    }

    suspend fun resetTrial(userId: Long): AdminActionDto {
        val service = subscriptions ?: return AdminActionDto(BILLING_OFF)
        val message = if (service.resetTrial(userId)) {
            "Пробный период сброшен: пользователь может включить его снова."
        } else {
            "Пробного периода у пользователя нет."
        }
        return AdminActionDto(message, card(userId))
    }

    suspend fun deleteData(userId: Long): AdminActionDto {
        if (isAdmin(userId)) return AdminActionDto("Данные админа отсюда не удаляются")
        val mailboxes = deleteUserData(userId)
        logger.info("Admin panel: data of user {} deleted ({} mailboxes)", userId, mailboxes)
        return AdminActionDto("Данные удалены, ящиков отключено: $mailboxes.")
    }

    private fun planOf(userId: Long, active: Subscription?): Plan = when {
        isAdmin(userId) -> Plan.BUSINESS
        subscriptions == null -> Plan.BUSINESS
        active != null -> active.plan
        subscriptions.enabled -> Plan.FREE
        else -> Plan.PRO
    }

    private suspend fun eventsDto(events: List<FunnelEvent>): List<AdminEventDto> {
        val names = botUsers.displayNames(events.map(FunnelEvent::userId).toSet())
        return events.map { event ->
            AdminEventDto(
                userId = event.userId,
                name = names[event.userId] ?: event.userId.toString(),
                step = event.step,
                title = FunnelStep.fromKey(event.step)?.title ?: event.step,
                detail = event.detail,
                at = event.at.toEpochMilli(),
            )
        }
    }

    private fun displayName(user: BotUserInfo): String =
        user.firstName?.takeIf(String::isNotBlank) ?: user.username?.let { name -> "@$name" } ?: user.userId.toString()

    private suspend fun notifyGranted(subscription: Subscription) {
        runCatching {
            val texts = botUsers.language(subscription.userId).texts
            telegramClient.sendMessage(
                chatId = subscription.userId,
                text = texts.billing.inviteActivated(subscription.plan, subscription.expiresAt),
            )
        }
    }

    private companion object {
        const val OVERVIEW_DAYS = 30
        const val EVENTS_LIMIT = 12
        const val MAX_USERS = 500
        const val BILLING_OFF = "Подписки не настроены"
    }
}
