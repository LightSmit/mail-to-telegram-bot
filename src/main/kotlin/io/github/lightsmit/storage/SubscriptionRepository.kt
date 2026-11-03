package io.github.lightsmit.storage

import io.github.lightsmit.billing.GrantTerm
import io.github.lightsmit.billing.Invite
import io.github.lightsmit.billing.Offer
import io.github.lightsmit.billing.Payment
import io.github.lightsmit.billing.Plan
import io.github.lightsmit.billing.Subscription
import io.github.lightsmit.billing.SubscriptionSource
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.time.Instant

/** Хранилище подписок, оплат и приглашений. Отдельный интерфейс - чтобы логику тарифов можно было проверить без базы. */
interface SubscriptionStore {
    suspend fun find(userId: Long): Subscription?
    suspend fun save(subscription: Subscription)
    suspend fun delete(userId: Long)
    suspend fun findAll(): List<Subscription>

    /** false - оплата с таким chargeId уже записана (Telegram прислал ее повторно). */
    suspend fun recordPayment(payment: Payment): Boolean
    suspend fun lastPayment(userId: Long): Payment?
    suspend fun markRefunded(chargeId: String)
    suspend fun countPayments(userId: Long): Int

    /** Сумма звезд за оплаты с момента since (null - за все время), без возвратов. */
    suspend fun revenueSince(since: Instant?): Long

    suspend fun createInvite(invite: Invite)
    suspend fun findInvite(code: String): Invite?

    /** Помечает приглашение использованным. null - его нет или им уже воспользовались. */
    suspend fun redeemInvite(code: String, userId: Long, now: Instant): Invite?
}

class SubscriptionRepository : SubscriptionStore {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS subscriptions (
                        user_id INTEGER PRIMARY KEY,
                        plan TEXT NOT NULL,
                        expires_at INTEGER,
                        source TEXT NOT NULL,
                        charge_id TEXT,
                        recurring INTEGER NOT NULL DEFAULT 0,
                        reminded_at INTEGER,
                        ended_notified_at INTEGER,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS payments (
                        charge_id TEXT PRIMARY KEY,
                        user_id INTEGER NOT NULL,
                        offer TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        recurring INTEGER NOT NULL DEFAULT 0,
                        refunded INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS payments_user ON payments (user_id, created_at)")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS invites (
                        code TEXT PRIMARY KEY,
                        plan TEXT NOT NULL,
                        term TEXT NOT NULL,
                        created_by INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        used_by INTEGER,
                        used_at INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override suspend fun find(userId: Long): Subscription? = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM subscriptions WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toSubscription() else null }
            }
        }
    }

    override suspend fun save(subscription: Subscription) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO subscriptions (user_id, plan, expires_at, source, charge_id, recurring, reminded_at, ended_notified_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(user_id) DO UPDATE SET
                        plan = excluded.plan,
                        expires_at = excluded.expires_at,
                        source = excluded.source,
                        charge_id = excluded.charge_id,
                        recurring = excluded.recurring,
                        reminded_at = excluded.reminded_at,
                        ended_notified_at = excluded.ended_notified_at,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, subscription.userId)
                    statement.setString(2, subscription.plan.name)
                    statement.setNullableLong(3, subscription.expiresAt?.toEpochMilli())
                    statement.setString(4, subscription.source.name)
                    statement.setString(5, subscription.chargeId)
                    statement.setInt(6, if (subscription.recurring) 1 else 0)
                    statement.setNullableLong(7, subscription.remindedAt?.toEpochMilli())
                    statement.setNullableLong(8, subscription.endedNotifiedAt?.toEpochMilli())
                    statement.setLong(9, subscription.updatedAt.toEpochMilli())
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun delete(userId: Long) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM subscriptions WHERE user_id = ?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun findAll(): List<Subscription> = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM subscriptions ORDER BY user_id").use { statement ->
                statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSubscription()) } }
            }
        }
    }

    override suspend fun recordPayment(payment: Payment): Boolean = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO payments (charge_id, user_id, offer, amount, recurring, refunded, created_at)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, payment.chargeId)
                statement.setLong(2, payment.userId)
                statement.setString(3, payment.offer.name)
                statement.setInt(4, payment.amount)
                statement.setInt(5, if (payment.recurring) 1 else 0)
                statement.setLong(6, payment.createdAt.toEpochMilli())
                statement.executeUpdate() == 1
            }
        }
    }

    override suspend fun lastPayment(userId: Long): Payment? = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM payments WHERE user_id = ? AND refunded = 0 ORDER BY created_at DESC LIMIT 1",
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toPayment() else null }
            }
        }
    }

    override suspend fun markRefunded(chargeId: String) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE payments SET refunded = 1 WHERE charge_id = ?").use { statement ->
                    statement.setString(1, chargeId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun countPayments(userId: Long): Int = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM payments WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override suspend fun revenueSince(since: Instant?): Long = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE refunded = 0 AND created_at >= ?",
            ).use { statement ->
                statement.setLong(1, since?.toEpochMilli() ?: 0L)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
    }

    override suspend fun createInvite(invite: Invite) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO invites (code, plan, term, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, invite.code)
                    statement.setString(2, invite.plan.name)
                    statement.setString(3, invite.term.spec)
                    statement.setLong(4, invite.createdBy)
                    statement.setLong(5, invite.createdAt.toEpochMilli())
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun findInvite(code: String): Invite? = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM invites WHERE code = ?").use { statement ->
                statement.setString(1, code)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toInvite() else null }
            }
        }
    }

    override suspend fun redeemInvite(code: String, userId: Long, now: Instant): Invite? = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            // Условие used_by IS NULL делает отметку атомарной: одной ссылкой воспользуется только один человек
            val updated = connection.prepareStatement(
                "UPDATE invites SET used_by = ?, used_at = ? WHERE code = ? AND used_by IS NULL",
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, now.toEpochMilli())
                statement.setString(3, code)
                statement.executeUpdate()
            }
            if (updated != 1) return@use null

            connection.prepareStatement("SELECT * FROM invites WHERE code = ?").use { statement ->
                statement.setString(1, code)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toInvite() else null }
            }
        }
    }

    private fun ResultSet.toSubscription() = Subscription(
        userId = getLong("user_id"),
        plan = Plan.parse(getString("plan")) ?: Plan.FREE,
        expiresAt = nullableLong("expires_at")?.let(Instant::ofEpochMilli),
        source = runCatching { SubscriptionSource.valueOf(getString("source")) }.getOrDefault(SubscriptionSource.GRANT),
        chargeId = getString("charge_id"),
        recurring = getInt("recurring") == 1,
        remindedAt = nullableLong("reminded_at")?.let(Instant::ofEpochMilli),
        endedNotifiedAt = nullableLong("ended_notified_at")?.let(Instant::ofEpochMilli),
        updatedAt = Instant.ofEpochMilli(getLong("updated_at")),
    )

    private fun ResultSet.toPayment(): Payment? {
        val offer = Offer.parse(getString("offer")) ?: return null
        return Payment(
            userId = getLong("user_id"),
            offer = offer,
            amount = getInt("amount"),
            chargeId = getString("charge_id"),
            recurring = getInt("recurring") == 1,
            createdAt = Instant.ofEpochMilli(getLong("created_at")),
            refunded = getInt("refunded") == 1,
        )
    }

    private fun ResultSet.toInvite() = Invite(
        code = getString("code"),
        plan = Plan.parse(getString("plan")) ?: Plan.PRO,
        term = GrantTerm.parse(getString("term")) ?: GrantTerm.Forever,
        createdBy = getLong("created_by"),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
        usedBy = nullableLong("used_by"),
        usedAt = nullableLong("used_at")?.let(Instant::ofEpochMilli),
    )

    private fun ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, java.sql.Types.INTEGER) else setLong(index, value)
    }
}
