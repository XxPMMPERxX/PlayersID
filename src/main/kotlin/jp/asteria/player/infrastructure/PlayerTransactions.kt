package jp.asteria.player.infrastructure

import jp.asteria.dbconnector.Database
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

/** プレイヤー行の寿命と同じDB境界で、関連データの更新を直列化する。 */
object PlayerTransactions {
    private class Guard {
        val tables = mutableSetOf<String>()
        var rejected = false
    }
    private val guardKey = Key<Guard>()
    private val writeTypes = setOf(StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE)
    @Volatile private var acceptingWrites = false
    private var registered = false
    private val interceptor = object : GlobalStatementInterceptor {
        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
            if (context.statement.type !in writeTypes) return
            var scope = transaction as? JdbcTransaction ?: return
            val db = scope.db
            while (true) {
                val guard = scope.getUserData(guardKey)
                if (guard != null && context.statement.targets.any { it.tableName in guard.tables }) {
                    // flushはDAOのpending値も変更するため、例外を捕捉されてもcommitさせない。
                    var rejectedScope: JdbcTransaction? = transaction as JdbcTransaction
                    while (rejectedScope != null && rejectedScope.db === db) {
                        rejectedScope.getUserData(guardKey)?.rejected = true
                        rejectedScope = rejectedScope.outerTransaction
                    }
                    error("ORM writes cannot be mixed with protected player SQL stores")
                }
                scope = scope.outerTransaction?.takeIf { it.db === db } ?: return
            }
        }

        override fun beforeCommit(transaction: Transaction) {
            var scope = transaction as? JdbcTransaction ?: return
            val db = scope.db
            while (true) {
                check(scope.getUserData(guardKey)?.rejected != true) {
                    "Rejected ORM writes require the whole player transaction to roll back"
                }
                scope = scope.outerTransaction?.takeIf { it.db === db } ?: return
            }
        }
    }

    /** 依存側のDB処理が始まる前に一度登録する。稼働中のリスト変更は行わない。 */
    @Synchronized
    internal fun initialize() {
        if (!registered) {
            JdbcTransaction.globalInterceptors.add(interceptor)
            registered = true
        }
        acceptingWrites = true
    }

    /** 既存トランザクションの保護は維持し、新規受付だけを止める。 */
    internal fun stop() { acceptingWrites = false }

    fun <T> write(playerId: Int, protectedTables: Collection<Table> = emptyList(), action: () -> T): T =
        write(listOf(playerId), protectedTables, action)

    fun <T> write(playerIds: Collection<Int>, protectedTables: Collection<Table> = emptyList(), action: () -> T): T {
        require(playerIds.isNotEmpty() && playerIds.all { it >= 0 })
        check(acceptingWrites) { "Player transaction service is not active" }
        check(Database.getTransactionConnection() == null) { "Player writes cannot join Database.transaction" }
        return transaction {
            protect(this, protectedTables)
            val connection = connection.connection as Connection
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

    /** 親にも保護を残し、後発childと親commit時のflushに適用する。 */
    private fun protect(scope: JdbcTransaction, tables: Collection<Table>) {
        if (tables.isEmpty()) return
        var current: JdbcTransaction? = scope
        while (current != null && current.db === scope.db) {
            val transaction = current
            val guard = transaction.getUserData(guardKey) ?: Guard().also {
                transaction.putUserData(guardKey, it)
            }
            guard.tables += tables.map { it.tableName }
            current = transaction.outerTransaction
        }
    }
}
