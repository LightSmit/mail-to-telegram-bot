package io.github.lightsmit.service

import io.github.lightsmit.billing.GrantTerm
import io.github.lightsmit.billing.Plan
import io.github.lightsmit.billing.Subscription
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.billing.SubscriptionSource
import io.github.lightsmit.i18n.Lang
import io.github.lightsmit.storage.BotUserInfo
import io.github.lightsmit.storage.BotUserRepository
import io.github.lightsmit.storage.FunnelEvent
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxOperation
import io.github.lightsmit.storage.MailOutboxStatus
import io.github.lightsmit.telegram.TelegramClient
import io.github.lightsmit.telegram.TelegramApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
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

@Serializable
data class AdminCountDto(val key: String, val title: String, val count: Int)

@Serializable
data class AdminStuckDto(
    val userId: Long,
    val name: String,
    val startedAt: Long,
    val source: String? = null,
    val lastStep: String,
    val error: String? = null,
)

@Serializable
data class AdminFunnelDto(
    val days: Int,
    val source: String? = null,
    val users: Int,
    val steps: List<AdminCountDto>,
    val sources: Map<String, Int>,
    val providers: Map<String, Int>,
    val errors: Map<String, Int>,
    val stuck: List<AdminStuckDto>,
)

@Serializable
data class AdminBrokenMailboxDto(
    val accountId: Long,
    val ownerId: Long,
    val ownerName: String,
    val email: String,
    val host: String,
    val problem: String? = null,
)

@Serializable
data class AdminSystemDto(
    val mailboxes: Int,
    val mailboxesOk: Int,
    val mailboxesError: Int,
    val mailboxesConnecting: Int,
    val forwardMailboxes: Int,
    val broken: List<AdminBrokenMailboxDto>,
    // Почтовые серверы: сколько ящиков на каждом
    val hosts: Map<String, Int>,
    val sentDay: Long,
    val sentWeek: Long,
    val deadWeek: Long,
    val queued: Long,
    val uptimeMillis: Long,
    val lastBackupAt: Long? = null,
    val lastBackupBytes: Long? = null,
    val usersBlocked: Long,
)

@Serializable
data class AdminSubscriptionDto(
    val userId: Long,
    val name: String,
    val plan: String,
    val source: String,
    val expiresAt: Long? = null,
    val recurring: Boolean,
)

@Serializable
data class AdminPaymentItemDto(
    val userId: Long,
    val name: String,
    val amount: Int,
    val offer: String,
    val at: Long,
    val refunded: Boolean,
)

@Serializable
data class AdminMoneyDto(
    val billingEnabled: Boolean,
    val revenue30: Long,
    val revenueTotal: Long,
    val pro: Int,
    val business: Int,
    val trials: Int,
    val lifetime: Int,
    val subscriptions: List<AdminSubscriptionDto>,
    val payments: List<AdminPaymentItemDto>,
)

@Serializable
data class AdminInviteDto(
    val message: String,
    val link: String? = null,
)

@Serializable
data class AdminAudienceDto(
    // Получателей в каждой аудитории (без заблокировавших бота)
    val counts: Map<String, Int>,
    val running: Boolean,
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
    // Имя бота для ссылок-приглашений
    private val botUsername: suspend () -> String? = { null },
    // Где идет рассылка: она долгая, приложение получает ответ сразу
    private val scope: CoroutineScope? = null,
    private val startedAt: Instant = Instant.now(),
) {
    private val logger = LoggerFactory.getLogger(AdminPanelService::class.java)
    private val broadcastRunning = AtomicBoolean(false)

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

    suspend fun funnel(days: Int, source: String?): AdminFunnelDto {
        val service = funnel ?: return AdminFunnelDto(days, source, 0, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList())
        val period = days.coerceIn(1, 365)
        val report = service.report(period, source?.takeIf(String::isNotBlank))
        val stuck = service.stuck(period)
            .filter { user -> source.isNullOrBlank() || user.source == source }
            .take(STUCK_LIMIT)
        val names = botUsers.displayNames(stuck.map(StuckUser::userId))
        return AdminFunnelDto(
            days = period,
            source = report.source,
            users = report.users,
            steps = FunnelStep.entries.map { step -> AdminCountDto(step.key, step.title, report.steps[step] ?: 0) },
            sources = report.sources,
            providers = report.providers,
            errors = report.errors,
            stuck = stuck.map { user ->
                AdminStuckDto(
                    userId = user.userId,
                    name = names[user.userId] ?: user.userId.toString(),
                    startedAt = user.startedAt.toEpochMilli(),
                    source = user.source,
                    lastStep = user.lastStep.title,
                    error = user.error,
                )
            },
        )
    }

    suspend fun system(): AdminSystemDto {
        val now = Instant.now()
        val day = now.minus(Duration.ofDays(1))
        val week = now.minus(Duration.ofDays(7))
        val all = accounts.findAll()
        val health = all.associate { account -> account.id to healthMonitor.health(account.id) }
        val brokenAccounts = all.filter { account -> health[account.id]?.status == MailboxStatus.ERROR }
        val names = botUsers.displayNames(brokenAccounts.map { account -> account.ownerChatId }.toSet())
        val backup = backupService.lastBackup
        return AdminSystemDto(
            mailboxes = all.size,
            mailboxesOk = health.values.count { value -> value.status == MailboxStatus.OK },
            mailboxesError = brokenAccounts.size,
            mailboxesConnecting = health.values.count { value -> value.status == MailboxStatus.CONNECTING },
            forwardMailboxes = all.count { account -> account.isForward },
            broken = brokenAccounts.map { account ->
                AdminBrokenMailboxDto(
                    accountId = account.id,
                    ownerId = account.ownerChatId,
                    ownerName = names[account.ownerChatId] ?: account.ownerChatId.toString(),
                    email = account.username,
                    host = account.host,
                    problem = health[account.id]?.problem?.text(Lang.RU.texts),
                )
            },
            hosts = all.groupingBy { account -> if (account.isForward) "пересылка" else account.host }
                .eachCount().entries.sortedByDescending { entry -> entry.value }.associate { entry -> entry.key to entry.value },
            sentDay = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, day),
            sentWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, week),
            deadWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.DEAD, week),
            queued = outbox.countByStatus(MailOutboxStatus.PENDING) + outbox.countByStatus(MailOutboxStatus.RETRY),
            uptimeMillis = Duration.between(startedAt, now).toMillis(),
            lastBackupAt = backup?.createdAt?.toEpochMilli(),
            lastBackupBytes = backup?.sizeBytes,
            usersBlocked = botUsers.countBlocked(),
        )
    }

    /** Бэкап базы сейчас: файл уходит админам в чат с ботом. */
    suspend fun backup(): AdminActionDto = try {
        val result = backupService.backupNow(notifyAdmins = true)
        AdminActionDto("Бэкап готов (${result.sizeBytes / 1024} КБ), файл отправлен в чат с ботом.")
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        logger.warn("Admin panel backup failed: {}", exception.message)
        AdminActionDto("Бэкап не удался: ${exception.message ?: exception.javaClass.simpleName}")
    }

    suspend fun money(): AdminMoneyDto {
        val service = subscriptions
            ?: return AdminMoneyDto(false, 0, 0, 0, 0, 0, 0, emptyList(), emptyList())
        val stats = service.stats()
        val active = service.activeSubscriptions()
            .filter { subscription -> !isAdmin(subscription.userId) }
            .sortedWith(compareBy<Subscription> { value -> value.source == SubscriptionSource.TRIAL }.thenBy { value -> value.expiresAt ?: Instant.MAX })
        val payments = service.recentPayments(PAYMENTS_LIMIT)
        val names = botUsers.displayNames((active.map(Subscription::userId) + payments.map { payment -> payment.userId }).toSet())
        return AdminMoneyDto(
            billingEnabled = service.enabled,
            revenue30 = stats.revenue30Days,
            revenueTotal = stats.revenueTotal,
            pro = stats.pro,
            business = stats.business,
            trials = stats.trials,
            lifetime = stats.lifetime,
            subscriptions = active.map { subscription ->
                AdminSubscriptionDto(
                    userId = subscription.userId,
                    name = names[subscription.userId] ?: subscription.userId.toString(),
                    plan = subscription.plan.name,
                    source = subscription.source.name,
                    expiresAt = subscription.expiresAt?.toEpochMilli(),
                    recurring = subscription.recurring,
                )
            },
            payments = payments.map { payment ->
                AdminPaymentItemDto(
                    userId = payment.userId,
                    name = names[payment.userId] ?: payment.userId.toString(),
                    amount = payment.amount,
                    offer = payment.offer.name,
                    at = payment.createdAt.toEpochMilli(),
                    refunded = payment.refunded,
                )
            },
        )
    }

    /** Одноразовая ссылка с подпиской: кто первым откроет, тот получит. */
    suspend fun invite(adminId: Long, planName: String, termSpec: String): AdminInviteDto {
        val service = subscriptions ?: return AdminInviteDto(BILLING_OFF)
        val plan = Plan.parse(planName)?.takeIf { value -> value != Plan.FREE }
            ?: return AdminInviteDto("Неизвестный тариф: $planName")
        val term = GrantTerm.parse(termSpec) ?: return AdminInviteDto("Не понял срок: $termSpec")
        val username = runCatching { botUsername() }.getOrNull()
            ?: return AdminInviteDto("Не удалось узнать имя бота у Telegram. Попробуйте еще раз.")
        val invite = service.createInvite(plan, term, adminId)
        return AdminInviteDto(
            message = "Ссылка создана. Кто откроет ее первым и нажмет «Начать», получит подписку.",
            link = "https://t.me/$username?start=${invite.code}",
        )
    }

    /** Сколько человек в каждой аудитории рассылки. */
    suspend fun audiences(): AdminAudienceDto =
        AdminAudienceDto(counts = BROADCAST_AUDIENCES.associateWith { audience -> recipients(audience).size }, running = broadcastRunning.get())

    /**
     * Рассылка. test - только себе, чтобы посмотреть, как выглядит сообщение.
     * Настоящая рассылка идет в фоне, итог бот пришлет админу.
     */
    suspend fun broadcast(adminId: Long, text: String, audience: String, test: Boolean): AdminActionDto {
        val message = text.trim()
        if (message.isEmpty()) return AdminActionDto("Напишите текст рассылки")
        if (message.length > MAX_BROADCAST_LENGTH) return AdminActionDto("Текст длиннее $MAX_BROADCAST_LENGTH символов")
        if (audience !in BROADCAST_AUDIENCES) return AdminActionDto("Неизвестная аудитория: $audience")

        if (test) {
            return try {
                telegramClient.sendMessage(chatId = adminId, text = message)
                AdminActionDto("Пробное сообщение отправлено вам в чат с ботом.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                AdminActionDto("Telegram не принял сообщение: ${exception.message ?: exception.javaClass.simpleName}")
            }
        }

        val runner = scope ?: return AdminActionDto("Рассылка недоступна")
        if (!broadcastRunning.compareAndSet(false, true)) return AdminActionDto("Другая рассылка еще идет. Дождитесь ее итога.")
        val ids = recipients(audience)
        if (ids.isEmpty()) {
            broadcastRunning.set(false)
            return AdminActionDto("В этой аудитории нет получателей")
        }
        runner.launch {
            try {
                val report = sendAll(ids, message)
                logger.info("Broadcast by {} to {} ({}): {}", adminId, audience, ids.size, report)
                runCatching { telegramClient.sendMessage(chatId = adminId, text = "📣 Рассылка завершена: $report.") }
            } finally {
                broadcastRunning.set(false)
            }
        }
        return AdminActionDto("Рассылка на ${ids.size} чел. запущена. Итог бот пришлет вам в чат.")
    }

    private suspend fun sendAll(ids: List<Long>, text: String): String {
        var delivered = 0
        var blocked = 0
        var failed = 0
        for (userId in ids) {
            try {
                telegramClient.sendMessage(chatId = userId, text = text)
                delivered++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: TelegramApiException) {
                if (exception.errorCode == 403) {
                    botUsers.markBlocked(userId)
                    blocked++
                } else {
                    failed++
                }
            } catch (exception: Exception) {
                failed++
            }
            // Лимит Telegram - около 30 сообщений в секунду
            delay(BROADCAST_PAUSE_MILLIS)
        }
        return "доставлено $delivered, заблокировали бота $blocked, ошибок $failed"
    }

    // Получатели аудитории: только те, кто не заблокировал бота
    private suspend fun recipients(audience: String): List<Long> {
        val reachable = botUsers.findReachableIds()
        if (audience == "all") return reachable
        val now = Instant.now()
        val owners = accounts.findAll().map { account -> account.ownerChatId }.toSet()
        val active = subscriptions?.allSubscriptions().orEmpty().filterValues { value -> value.isActive(now) }
        return reachable.filter { userId ->
            val subscription = active[userId]
            when (audience) {
                "mailbox" -> userId in owners
                "nobox" -> userId !in owners
                "paid" -> subscription != null && subscription.source != SubscriptionSource.TRIAL
                "trial" -> subscription?.source == SubscriptionSource.TRIAL
                "free" -> subscription == null && !isAdmin(userId)
                else -> false
            }
        }
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
        const val STUCK_LIMIT = 50
        const val PAYMENTS_LIMIT = 30
        const val MAX_BROADCAST_LENGTH = 4000
        const val BROADCAST_PAUSE_MILLIS = 50L
        val BROADCAST_AUDIENCES = listOf("all", "mailbox", "nobox", "paid", "trial", "free")
    }
}
