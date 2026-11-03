package io.github.lightsmit.service

import io.github.lightsmit.billing.GrantTerm
import io.github.lightsmit.billing.ReferralService
import io.github.lightsmit.billing.Plan
import io.github.lightsmit.billing.Subscription
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.billing.SubscriptionSource
import io.github.lightsmit.storage.BotUserRepository
import io.github.lightsmit.storage.MailAccountRepository
import io.github.lightsmit.storage.MailNotificationOutboxRepository
import io.github.lightsmit.storage.MailOutboxOperation
import io.github.lightsmit.storage.MailOutboxStatus
import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Команды администратора: статистика, рассылка, ручной бэкап, подписки. */
class AdminService(
    val adminChatIds: Set<Long>,
    private val botUsers: BotUserRepository,
    private val accounts: MailAccountRepository,
    private val outbox: MailNotificationOutboxRepository,
    private val healthMonitor: MailboxHealthMonitor,
    private val backupService: BackupService,
    private val telegramClient: TelegramClient,
    // Тарифы и подписки; null - команды /grant, /revoke и др. не работают
    private val subscriptions: SubscriptionService? = null,
    // Приглашения друзей: /refs
    private val referrals: ReferralService? = null,
    private val startedAt: Instant = Instant.now(),
) {
    private val logger = LoggerFactory.getLogger(AdminService::class.java)
    private val pendingBroadcasts = ConcurrentHashMap<Long, String>()
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    private val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())

    // Имя бота для ссылок-приглашений: узнаем у Telegram один раз
    @Volatile
    private var botUsername: String? = null

    fun isAdmin(userId: Long): Boolean = userId in adminChatIds

    suspend fun statsText(): String {
        val now = Instant.now()
        val day = now.minus(Duration.ofDays(1))
        val week = now.minus(Duration.ofDays(7))

        val allAccounts = accounts.findAll()
        val statuses = allAccounts.groupingBy { account -> healthMonitor.health(account.id).status }.eachCount()

        val sentDay = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, day)
        val sentWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.SENT, week)
        val deadWeek = outbox.countSince(MailOutboxOperation.SEND_NOTIFICATION, MailOutboxStatus.DEAD, week)
        val queued = outbox.countByStatus(MailOutboxStatus.PENDING) + outbox.countByStatus(MailOutboxStatus.RETRY)

        val uptime = Duration.between(startedAt, now)
        val backup = backupService.lastBackup

        return buildString {
            appendLine("📊 Статистика")
            appendLine()
            appendLine("Пользователи")
            appendLine("всего: ${botUsers.count()}, с ящиками: ${accounts.countOwners()}")
            appendLine("новых за неделю: ${botUsers.countFirstSeenSince(week)}, активных за неделю: ${botUsers.countSeenSince(week)}")
            appendLine("заблокировали бота: ${botUsers.countBlocked()}")
            appendLine()
            appendLine("Ящики: ${allAccounts.size}")
            appendLine("🟢 работают: ${statuses[MailboxStatus.OK] ?: 0}")
            appendLine("🟡 подключаются: ${statuses[MailboxStatus.CONNECTING] ?: 0}")
            appendLine("🔴 с ошибкой: ${statuses[MailboxStatus.ERROR] ?: 0}")
            appendLine()
            appendLine("Уведомления о письмах")
            appendLine("за сутки: $sentDay, за неделю: $sentWeek")
            appendLine("не доставлено за неделю: $deadWeek, в очереди сейчас: $queued")
            subscriptions?.let { service ->
                val billing = service.stats()
                appendLine()
                appendLine("Подписки" + if (service.enabled) "" else " (оплата выключена: BILLING_ENABLED=false)")
                appendLine("Pro: ${billing.pro}, Business: ${billing.business}, пробных: ${billing.trials}, из них навсегда: ${billing.lifetime}")
                appendLine("Доход: за 30 дней ${billing.revenue30Days}⭐, всего ${billing.revenueTotal}⭐")
            }
            appendLine()
            appendLine("Работает без перезапуска: ${uptime.toDays()} д ${uptime.toHoursPart()} ч ${uptime.toMinutesPart()} мин")
            append("Последний бэкап: ")
            append(backup?.let { "${dateTime.format(it.createdAt)}, ${it.sizeBytes / 1024} КБ" } ?: "еще не было с момента запуска")
            appendLine()
            appendLine()
            appendLine("Команды: /broadcast текст - рассылка всем, /backup - бэкап сейчас")
            if (subscriptions != null) {
                appendLine("Подписки: /subs, /users, /refs, /grant, /revoke, /invite, /refund (без параметров - подсказка)")
            }
        }.trimEnd()
    }

    /** /grant <id или @username> <pro|business> <1m|3m|6m|1y|7d|forever> */
    suspend fun grantText(argument: String): String {
        val service = subscriptions ?: return BILLING_UNAVAILABLE
        val parts = argument.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (parts.size != 3) return GRANT_USAGE

        val userId = resolveUser(parts[0]) ?: return userNotFound(parts[0])
        val plan = Plan.parse(parts[1])?.takeIf { value -> value != Plan.FREE } ?: return GRANT_USAGE
        val term = GrantTerm.parse(parts[2]) ?: return GRANT_USAGE

        val subscription = service.grant(userId, plan, term, SubscriptionSource.GRANT)
        notifyGranted(subscription)
        return "✅ ${userLabel(userId)}: ${plan.title} ${untilText(subscription)}."
    }

    /** /revoke <id или @username> */
    suspend fun revokeText(argument: String): String {
        val service = subscriptions ?: return BILLING_UNAVAILABLE
        val target = argument.trim().takeIf(String::isNotEmpty) ?: return REVOKE_USAGE
        val userId = resolveUser(target) ?: return userNotFound(target)

        val result = service.revoke(userId)
        if (!result.hadSubscription && !result.renewalCancelled) {
            return "У ${userLabel(userId)} нет действующей подписки."
        }
        return buildString {
            append("✅ Подписка ${result.previousPlan?.title ?: ""} у ${userLabel(userId)} отозвана, теперь Free.")
            if (result.renewalCancelled) append(" Автопродление Stars отменено.")
            if (service.isAdmin(userId)) append(" Учтите: это админ из .env, у него все равно будет Business.")
        }
    }

    /** /invite <pro|business> <срок>: одноразовая ссылка с подпиской. */
    suspend fun inviteText(adminId: Long, argument: String): String {
        val service = subscriptions ?: return BILLING_UNAVAILABLE
        val parts = argument.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (parts.size != 2) return INVITE_USAGE
        val plan = Plan.parse(parts[0])?.takeIf { value -> value != Plan.FREE } ?: return INVITE_USAGE
        val term = GrantTerm.parse(parts[1]) ?: return INVITE_USAGE

        val username = botUsername ?: runCatching { telegramClient.getBotUsername() }.getOrNull()?.also { name -> botUsername = name }
            ?: return "Не удалось узнать имя бота у Telegram. Попробуйте еще раз."
        val invite = service.createInvite(plan, term, adminId)
        val termText = if (term is GrantTerm.Forever) "навсегда" else "на ${describe(term)}"
        return "🎁 Одноразовая ссылка: ${plan.title} $termText\n\nhttps://t.me/$username?start=${invite.code}\n\n" +
            "Кто откроет ее первым и нажмет «Начать», получит подписку. Если подписка уже есть, срок добавится."
    }

    /** /subs: все действующие подписки. */
    suspend fun subscriptionsText(): String {
        val service = subscriptions ?: return BILLING_UNAVAILABLE
        val active = service.activeSubscriptions().sortedWith(compareBy({ it.plan }, { it.expiresAt ?: Instant.MAX }))
        if (active.isEmpty()) return "Действующих подписок нет." + if (adminChatIds.isNotEmpty()) " Админы из .env всегда на Business." else ""

        val names = botUsers.displayNames(active.map(Subscription::userId))
        return buildString {
            appendLine("⭐ Действующие подписки: ${active.size}")
            appendLine()
            active.take(LIST_LIMIT).forEach { subscription ->
                append("${subscription.plan.title} ${untilText(subscription)} · ${sourceText(subscription)}")
                appendLine()
                append("    ").append(subscription.userId)
                names[subscription.userId]?.let { name -> append(" ").append(name) }
                appendLine()
            }
            if (active.size > LIST_LIMIT) appendLine("...и еще ${active.size - LIST_LIMIT}")
            appendLine()
            append("Админы из .env всегда на Business и в списке не показаны.")
        }.trimEnd()
    }

    /** /users: последние активные пользователи с ID и тарифом. */
    suspend fun usersText(): String {
        val users = botUsers.recent(LIST_LIMIT)
        if (users.isEmpty()) return "Пользователей пока нет."
        return buildString {
            appendLine("👥 Последние пользователи (${users.size})")
            appendLine()
            users.forEach { user ->
                append(user.userId)
                user.username?.let { name -> append(" @").append(name) }
                user.firstName?.let { name -> append(" ").append(name) }
                subscriptions?.let { service -> append(" · ").append(service.plan(user.userId).title) }
                if (user.blocked) append(" · заблокировал бота")
                appendLine()
                append("    был ").append(dateTime.format(user.lastSeenAt))
                appendLine()
            }
            appendLine()
            append("Выдать подписку: /grant ID pro 1m")
        }.trimEnd()
    }

    /** /refs: кто сколько друзей пригласил. */
    suspend fun referralsText(): String {
        val service = referrals ?: return "Приглашения не настроены."
        val top = service.top(LIST_LIMIT)
        if (top.isEmpty()) return "По реферальным ссылкам пока никто не приходил."
        val names = botUsers.displayNames(top.map { summary -> summary.referrerId })
        return buildString {
            appendLine("🎁 Приглашения (награда за каждых ${service.goal} засчитанных друзей)")
            appendLine()
            top.forEach { summary ->
                append(summary.referrerId)
                names[summary.referrerId]?.let { name -> append(" ").append(name) }
                append(": пришли ${summary.invited}, засчитано ${summary.qualified}")
                if (service.goal > 0) append(", наград ${summary.qualified / service.goal}")
                appendLine()
            }
            appendLine()
            append("Засчитан - новый пользователь пришел по ссылке и подключил ящик.")
        }.trimEnd()
    }

    /** /refund <id или @username>: вернуть звезды за последнюю оплату и отозвать подписку. */
    suspend fun refundText(argument: String): String {
        val service = subscriptions ?: return BILLING_UNAVAILABLE
        val target = argument.trim().takeIf(String::isNotEmpty)
            ?: return "Как вернуть звезды: /refund ID (или @username). Вернется последняя оплата, подписка будет отозвана."
        val userId = resolveUser(target) ?: return userNotFound(target)

        return when (val result = service.refundLast(userId)) {
            SubscriptionService.RefundResult.NoPayment -> "У ${userLabel(userId)} нет оплат, которые можно вернуть."
            is SubscriptionService.RefundResult.Refunded ->
                "✅ ${result.payment.amount}⭐ возвращены ${userLabel(userId)}, подписка отозвана."
            is SubscriptionService.RefundResult.Failed ->
                "⚠️ Telegram не принял возврат ${result.payment.amount}⭐: ${result.reason}"
        }
    }

    private suspend fun notifyGranted(subscription: Subscription) {
        runCatching {
            val texts = botUsers.language(subscription.userId).texts
            telegramClient.sendMessage(
                chatId = subscription.userId,
                text = texts.billing.inviteActivated(subscription.plan, subscription.expiresAt),
            )
        }
    }

    private suspend fun resolveUser(value: String): Long? {
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.let { id -> return id.takeIf { it > 0 } }
        if (!trimmed.startsWith("@")) return null
        return botUsers.findIdByUsername(trimmed)
    }

    private suspend fun userLabel(userId: Long): String =
        botUsers.displayNames(listOf(userId))[userId]?.let { name -> "$name ($userId)" } ?: userId.toString()

    private fun userNotFound(value: String) =
        "Пользователь $value не найден. Укажите числовой ID или @username человека, который уже писал боту (список: /users)."

    private fun untilText(subscription: Subscription): String =
        subscription.expiresAt?.let { expires -> "до ${date.format(expires)}" } ?: "навсегда"

    private fun sourceText(subscription: Subscription): String = when (subscription.source) {
        SubscriptionSource.TRIAL -> "пробный"
        SubscriptionSource.STARS -> if (subscription.recurring) "Stars, автопродление" else "Stars"
        SubscriptionSource.GRANT -> "выдана"
        SubscriptionSource.INVITE -> "по ссылке"
        SubscriptionSource.REFERRAL -> "за друзей"
    }

    private fun describe(term: GrantTerm): String = when (term) {
        GrantTerm.Forever -> "навсегда"
        is GrantTerm.Span -> {
            val amount = term.spec.dropLast(1)
            when (term.spec.last()) {
                'd' -> "$amount дн."
                'm' -> "$amount мес."
                else -> "$amount г."
            }
        }
    }

    /** Первый шаг рассылки: запоминаем текст и показываем предпросмотр. */
    suspend fun prepareBroadcast(adminId: Long, text: String): String {
        if (text.isBlank()) {
            return "Напишите текст после команды: /broadcast Привет! У нас обновление..."
        }
        pendingBroadcasts[adminId] = text
        val recipients = botUsers.findReachableIds().size
        return buildString {
            appendLine("Предпросмотр рассылки для $recipients пользователей:")
            appendLine()
            appendLine(text)
            appendLine()
            append("Отправить: /broadcast_send, отменить: /broadcast_cancel")
        }
    }

    fun cancelBroadcast(adminId: Long): String =
        if (pendingBroadcasts.remove(adminId) != null) "Рассылка отменена." else "Нет подготовленной рассылки."

    /** Второй шаг: отправка. Вызывать в фоне: при многих пользователях идет долго. */
    suspend fun sendBroadcast(adminId: Long): String {
        val text = pendingBroadcasts.remove(adminId)
            ?: return "Нет подготовленной рассылки. Сначала: /broadcast текст"

        var delivered = 0
        var blocked = 0
        var failed = 0

        for (userId in botUsers.findReachableIds()) {
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

        logger.info("Broadcast by {}: delivered {}, blocked {}, failed {}", adminId, delivered, blocked, failed)
        return "Рассылка завершена: доставлено $delivered, заблокировали бота $blocked, ошибок $failed."
    }

    suspend fun backupText(): String {
        val result = backupService.backupNow(notifyAdmins = true)
        return "Бэкап готов: ${result.file.fileName}, ${result.sizeBytes / 1024} КБ. Файл отправлен администраторам."
    }

    private companion object {
        const val BROADCAST_PAUSE_MILLIS = 50L
        const val LIST_LIMIT = 40
        const val BILLING_UNAVAILABLE = "Подписки не настроены."
        const val GRANT_USAGE = "Как выдать подписку: /grant ID тариф срок\n" +
            "Тариф: pro или business. Срок: 7d, 1m, 3m, 6m, 1y или forever (навсегда).\n" +
            "Пример: /grant 123456789 pro 1m или /grant @username business forever\n" +
            "Если такой тариф уже есть, срок добавится к текущему."
        const val REVOKE_USAGE = "Как отозвать подписку: /revoke ID (или @username). Тариф сразу станет Free, автопродление Stars отменится."
        const val INVITE_USAGE = "Как создать ссылку: /invite тариф срок\n" +
            "Пример: /invite business forever или /invite pro 1m. Ссылка одноразовая."
    }
}
