package jp.asteria.player.infrastructure

import jp.asteria.dbconnector.CanonicalRequest
import jp.asteria.dbconnector.SqlOperationLedger
import jp.asteria.dbconnector.SqlStoreTransactions
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.util.UUID

/** 新規IDを作った事実。欠損は旧データの変換や再初期化を許可しない。 */
class PlayerCreation internal constructor(val playerId: Int, val operationId: UUID, val sourceId: String)

object PlayerCreations {
    private const val OWNER = "PlayersID"
    private val definitions = mapOf("identity-creation" to "1")
    private object Creations : Table("player_identity_creations_v1") {
        val playerId = reference("playerId", PlayersTable.id)
        val operationId = varchar("operationId", 36).uniqueIndex()
        val sourceId = varchar("sourceId", 256).uniqueIndex()
        val identityHash = varchar("identityHash", 64)
        override val primaryKey = PrimaryKey(playerId)
    }

    internal fun initializeSchema() {
        SqlOperationLedger.initializeSchema()
        SqlStoreTransactions.write { SchemaUtils.create(Creations) }
    }

    /** 外側の保存境界で読む。破損や不一致はnullへ変換せず、処理を止める。 */
    fun findInCurrentTransaction(playerId: Int): PlayerCreation? {
        require(playerId >= 0)
        val row = connection().prepareStatement(
            "SELECT c.operationId,c.sourceId,c.identityHash,p.xuid FROM player_identity_creations_v1 c JOIN players p ON p.id=c.playerId WHERE c.playerId=?"
        ).use { sql ->
            sql.setInt(1, playerId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) return null
                arrayOf(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))
            }
        }
        val operationId = UUID.fromString(row[0])
        val sourceId = source(playerId)
        val identityHash = CanonicalRequest.sha256(row[3].toByteArray(Charsets.UTF_8))
        check(row[1] == sourceId && row[2] == identityHash) { "Identity creation requires reconciliation" }
        val receipt = checkNotNull(SqlOperationLedger.query(OWNER, operationId)) { "Missing creation receipt" }
        check(receipt.state == SqlOperationLedger.State.COMMITTED && receipt.sourceId == sourceId &&
            receipt.requestHash == request(playerId, identityHash).hash &&
            receipt.resultBytes().contentEquals(result(playerId))) { "Invalid creation receipt" }
        return PlayerCreation(playerId, operationId, sourceId)
    }

    /** IDのINSERTを行った経路だけが呼ぶ。既存行のログインからは作らない。 */
    internal fun recordInsertedIdentity(playerId: Int, xuid: String) {
        SqlStoreTransactions.write(listOf(Creations)) {
            val identityHash = CanonicalRequest.sha256(xuid.toByteArray(Charsets.UTF_8))
            val operationId = UUID.randomUUID()
            val sourceId = source(playerId)
            SqlOperationLedger.enlist(OWNER, operationId, sourceId, request(playerId, identityHash),
                listOf(playerId), definitions) {
                connection().prepareStatement(
                    "INSERT INTO player_identity_creations_v1 (playerId,operationId,sourceId,identityHash) VALUES (?,?,?,?)"
                ).use { sql ->
                    sql.setInt(1, playerId)
                    sql.setString(2, operationId.toString())
                    sql.setString(3, sourceId)
                    sql.setString(4, identityHash)
                    check(sql.executeUpdate() == 1)
                }
                SqlOperationLedger.Outcome(SqlOperationLedger.State.COMMITTED, result(playerId))
            }
        }
    }

    private fun source(playerId: Int) = "players-id/creation/$playerId"
    private fun request(playerId: Int, identityHash: String) =
        CanonicalRequest(mapOf("schema" to 1, "playerId" to playerId, "identityHash" to identityHash))
    private fun result(playerId: Int) = CanonicalRequest(mapOf("createdPlayerId" to playerId, "schema" to 1)).bytes()
    private fun connection(): Connection = TransactionManager.current().connection.connection as Connection
}
