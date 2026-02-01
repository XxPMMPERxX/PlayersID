package jp.asteria.player

import cn.nukkit.OfflinePlayer
import cn.nukkit.Player
import cn.nukkit.event.EventHandler
import cn.nukkit.event.EventPriority
import cn.nukkit.event.Listener
import cn.nukkit.event.player.PlayerJoinEvent
import cn.nukkit.plugin.PluginBase
import jp.asteria.dbconnector.Database
import jp.asteria.dbconnector.Database.transaction
import java.sql.SQLException
import java.sql.Timestamp

class PlayersID : PluginBase(), Listener {
    companion object {
        internal const val NBT_NAME = "jp.asteria.player.PlayersID"
    }

    override fun onEnable() {
        transaction {
            val connection = Database.getConnection() ?:throw SQLException()
            val stmt = connection.prepareStatement(
                """
                    CREATE TABLE IF NOT EXISTS players (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        xuid VARCHAR(20) UNIQUE NOT NULL,
                        discord_id VARCHAR(255) UNIQUE,
                        gamertag VARCHAR(255) NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                """.trimIndent()
            )
            stmt.executeUpdate()
        }
        server.pluginManager.registerEvents(this, this)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onLogin(event: PlayerJoinEvent) {
        savePlayerData(event.player)
    }

    private fun savePlayerData(player: Player) {
        transaction {
            val connection = Database.getConnection() ?: throw SQLException()

            // 存在確認
            val stmt1 = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM players WHERE xuid = ?) AS flg")
            var exists: Boolean
            stmt1.use {
                stmt1.setString(1, player.xuid)
                val result = stmt1.executeQuery()
                result.use {
                    exists = if (result.next()) result.getBoolean("flg") else false
                }
            }

            if (exists) {
                // 存在する場合はゲーマータグの更新
                val stmt2 = connection.prepareStatement("UPDATE players SET gamertag = ?, updated_at = ? WHERE id = ?")
                stmt2.setString(1, player.name)
                stmt2.setTimestamp(2, Timestamp(System.currentTimeMillis()))
                stmt2.setInt(3, player.primaryId!!)
                stmt2.executeUpdate()
            } else {
                // 存在しない場合は新規作成
                val stmt2 = connection.prepareStatement("INSERT INTO players (xuid, gamertag, created_at, updated_at) VALUES (?, ?, ?, ?)")
                stmt2.setString(1, player.xuid)
                stmt2.setString(2, player.name)
                stmt2.setTimestamp(3, Timestamp(System.currentTimeMillis()))
                stmt2.setTimestamp(4, Timestamp(System.currentTimeMillis()))
                stmt2.executeUpdate()

                // autoincrementのidをNBTにセット
                val stmt3 = connection.prepareStatement("SELECT id FROM players WHERE xuid = ?")
                stmt3.setString(1, player.xuid)
                val result2 = stmt3.executeQuery()
                result2.use {
                    player.namedTag.putInt(NBT_NAME, result2.getInt("id"))
                }
            }
        }
    }
}

/**
 * DBのプライマリーキー
 */
val OfflinePlayer.primaryId: Int?
    get() {
        val id = server.getOfflinePlayerData(uniqueId, false).getInt(PlayersID.NBT_NAME, -1)
        return if (id < 0) null else id
    }

/**
 * DBのプライマリーキー
 */
val Player.primaryId: Int?
    get() {
        val id = namedTag.getInt(PlayersID.NBT_NAME, -1)
        return if (id < 0) null else id
    }
