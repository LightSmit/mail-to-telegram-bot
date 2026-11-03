package io.github.lightsmit.storage

import kotlinx.coroutines.withContext
import java.time.Instant

/** Кто кого пригласил. Подключил ли приглашенный ящик (qualifiedAt) - только тогда друг засчитывается. */
data class Referral(
    val invitedId: Long,
    val referrerId: Long,
    val createdAt: Instant,
    val qualifiedAt: Instant? = null,
)

/** Пригласивший и сколько у него друзей: для /refs у администратора. */
data class ReferrerSummary(val referrerId: Long, val invited: Int, val qualified: Int)

interface ReferralStore {
    suspend fun find(invitedId: Long): Referral?
    /** false - этот человек уже был приглашен (засчитывается только первая ссылка). */
    suspend fun insert(referral: Referral): Boolean
    suspend fun markQualified(invitedId: Long, now: Instant)
    suspend fun countInvited(referrerId: Long): Int
    suspend fun countQualified(referrerId: Long): Int
    suspend fun top(limit: Int): List<ReferrerSummary>
}

class ReferralRepository : ReferralStore {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS referrals (
                        invited_id INTEGER PRIMARY KEY,
                        referrer_id INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        qualified_at INTEGER
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS referrals_referrer ON referrals (referrer_id)")
            }
        }
    }

    override suspend fun find(invitedId: Long): Referral? = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM referrals WHERE invited_id = ?").use { statement ->
                statement.setLong(1, invitedId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    val qualified = rs.getLong("qualified_at").takeUnless { rs.wasNull() }
                    Referral(
                        invitedId = rs.getLong("invited_id"),
                        referrerId = rs.getLong("referrer_id"),
                        createdAt = Instant.ofEpochMilli(rs.getLong("created_at")),
                        qualifiedAt = qualified?.let(Instant::ofEpochMilli),
                    )
                }
            }
        }
    }

    override suspend fun insert(referral: Referral): Boolean = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT OR IGNORE INTO referrals (invited_id, referrer_id, created_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, referral.invitedId)
                statement.setLong(2, referral.referrerId)
                statement.setLong(3, referral.createdAt.toEpochMilli())
                statement.executeUpdate() == 1
            }
        }
    }

    override suspend fun markQualified(invitedId: Long, now: Instant) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE referrals SET qualified_at = ? WHERE invited_id = ? AND qualified_at IS NULL",
                ).use { statement ->
                    statement.setLong(1, now.toEpochMilli())
                    statement.setLong(2, invitedId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun countInvited(referrerId: Long): Int = count("SELECT COUNT(*) FROM referrals WHERE referrer_id = ?", referrerId)

    override suspend fun countQualified(referrerId: Long): Int =
        count("SELECT COUNT(*) FROM referrals WHERE referrer_id = ? AND qualified_at IS NOT NULL", referrerId)

    override suspend fun top(limit: Int): List<ReferrerSummary> = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT referrer_id, COUNT(*) AS invited, COUNT(qualified_at) AS qualified
                FROM referrals GROUP BY referrer_id
                ORDER BY qualified DESC, invited DESC LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(ReferrerSummary(rs.getLong(1), rs.getInt(2), rs.getInt(3))) }
                }
            }
        }
    }

    private suspend fun count(sql: String, id: Long): Int = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }
}
