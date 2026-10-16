package jp.asteria.player.infrastructure

import jp.asteria.dbconnector.SqlStoreTransactions
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.util.Collections

/** 対象行を先にロックした一つのSQL保存境界。実体操作や接続の持出しには使わない。 */
class PlayerWriteContext private constructor(
    playerIds: List<Int>,
    private val connection: Connection
) {
    companion object {
        @JvmSynthetic
        internal fun create(playerIds: List<Int>, connection: Connection) = PlayerWriteContext(playerIds, connection)
    }

    private val thread = Thread.currentThread()
    val playerIds: List<Int> = Collections.unmodifiableList(playerIds.toList())
    @Volatile private var failed = false
    @Volatile private var open = true

    /** 参加者は同じ接続で自分のテーブルだけを保存する。ロック対象は追加できない。 */
    fun <T> enlist(playerId: Int, protectedTables: Collection<Table> = emptyList(), action: () -> T): T {
        validateParticipants(listOf(playerId))
        return SqlStoreTransactions.write(protectedTables) {
            validateParticipants(listOf(playerId))
            action()
        }
    }

    internal fun validateParticipants(ids: Collection<Int>) {
        try {
            check(open) { "Player write context is closed" }
            validateConnection()
            require(ids.isNotEmpty() && ids.all { it in playerIds }) { "Player lock set cannot expand" }
            check(!failed) { "Player write context requires rollback" }
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
    }

    internal fun close() { open = false }

    internal fun rejectNestedRoot(): Nothing {
        failed = true
        error("A closed player write cannot start another root")
    }

    /** action終了後のphysical commitでも検査する。閉じたcontextは再利用できない。 */
    internal fun validateCommit() {
        validateConnection()
        check(!failed) { "Player write context requires rollback" }
    }

    private fun validateConnection() {
        check(Thread.currentThread() === thread) { "Player write context cannot cross threads" }
        val current = TransactionManager.currentOrNull()
        check(current != null && current.connection.connection === connection) {
            "Player write context requires its original SQL transaction"
        }
    }
}
