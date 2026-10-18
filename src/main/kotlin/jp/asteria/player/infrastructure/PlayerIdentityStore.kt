package jp.asteria.player.infrastructure

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.time.Clock

/** ID確定・名前更新を保存してから、接続側へ不変値だけを返す。 */
internal object PlayerIdentityStore {
    fun load(xuid: String, gamerTag: String): PlayerIdentity {
        check(org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.currentOrNull() == null) {
            "Player identity must load outside Exposed transactions"
        }
        check(jp.asteria.dbconnector.Database.getTransactionConnection() == null) {
            "Player identity cannot join Database.transaction"
        }
        return transaction {
            val connection = connection.connection as Connection
            val existingId = connection.prepareStatement("SELECT id FROM players WHERE xuid=?").use {
                it.setString(1, xuid)
                it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
            }
            val playerId = existingId ?: PlayerEntity.new { this.xuid = xuid; this.gamerTag = gamerTag }.id.value
            PlayerTransactions.write(playerId) {
                if (existingId == null) PlayerCreations.recordInsertedIdentity(playerId, xuid)
                val row = PlayersTable.selectAll().where { PlayersTable.id eq playerId }.forUpdate().single()
                val player = PlayerEntity.wrapRow(row)
                if (existingId != null) {
                    player.gamerTag = gamerTag
                    player.updatedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                }
                PlayerIdentity(playerId, row[PlayersTable.discordId])
            }
        }
    }
}
