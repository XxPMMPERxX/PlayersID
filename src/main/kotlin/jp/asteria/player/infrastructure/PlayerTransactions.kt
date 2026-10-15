package jp.asteria.player.infrastructure

import jp.asteria.dbconnector.SqlStoreTransactions
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection

/** プレイヤー行の寿命と同じDB境界で、関連データの更新を直列化する。 */
object PlayerTransactions {
    @Volatile private var acceptingWrites = false

    @Synchronized
    internal fun initialize() {
        SqlStoreTransactions.initialize()
        acceptingWrites = true
    }

    /** 既存トランザクションの保護は維持し、新規受付だけを止める。 */
    internal fun stop() { acceptingWrites = false }

    fun <T> write(playerId: Int, protectedTables: Collection<Table> = emptyList(), action: () -> T): T =
        write(listOf(playerId), protectedTables, action)

    fun <T> write(playerIds: Collection<Int>, protectedTables: Collection<Table> = emptyList(), action: () -> T): T {
        require(playerIds.isNotEmpty() && playerIds.all { it >= 0 })
        check(acceptingWrites) { "Player transaction service is not active" }
        return SqlStoreTransactions.write(protectedTables) {
            val connection = TransactionManager.current().connection.connection as Connection
            check(!connection.autoCommit) { "Player writes require an active JDBC transaction" }
            val suffix = when (connection.metaData.databaseProductName) {
                "MySQL", "MariaDB" -> " FOR UPDATE"
                "SQLite" -> ""
                else -> error("Unsupported player database: ${connection.metaData.databaseProductName}")
            }
            for (id in playerIds.distinct().sorted()) {
                // DSLのロックSQLは実行前にORMの全pending書込みをflushするため使わない。
                connection.prepareStatement("UPDATE players SET id=id WHERE id=?").use {
                    it.setInt(1, id)
                    it.executeUpdate()
                }
                // MySQLでは変更件数0でも行が存在し得る。RRの古いsnapshotも使わない。
                connection.prepareStatement("SELECT id FROM players WHERE id=?$suffix").use {
                    it.setInt(1, id)
                    it.executeQuery().use { rows -> require(rows.next()) { "Unknown player: $id" } }
                }
            }
            action()
        }
    }
}
