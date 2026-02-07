package jp.asteria.player

import cn.nukkit.IPlayer
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
        internal const val NBT_PRIMARY_KEY = "jp.asteria.player.PlayersID.PrimaryKey"
        internal const val NBT_DISCORD_ID = "jp.asteria.player.PlayersID.DiscordId"
    }

    override fun onEnable() {
        transaction {
            val connection = Database.getConnection() ?: throw SQLException()
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
        loadPlayerData(event.player)
    }

    private fun loadPlayerData(player: Player) {
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
                val stmt2 =
                    connection.prepareStatement("INSERT INTO players (xuid, gamertag, created_at, updated_at) VALUES (?, ?, ?, ?)")
                stmt2.setString(1, player.xuid)
                stmt2.setString(2, player.name)
                stmt2.setTimestamp(3, Timestamp(System.currentTimeMillis()))
                stmt2.setTimestamp(4, Timestamp(System.currentTimeMillis()))
                stmt2.executeUpdate()
            }

            val stmt3 = connection.prepareStatement("SELECT id, discord_id FROM players WHERE xuid = ?")
            stmt3.use {
                stmt3.setString(1, player.xuid)
                val result3 = stmt3.executeQuery()
                result3.use {
                    player.namedTag.putInt(NBT_PRIMARY_KEY, result3.getInt("id"))
                    player.namedTag.putString(NBT_DISCORD_ID, result3.getString("discord_id") ?: "")
                }
            }
        }
    }
}

/**
 * DBのプライマリーキー
 */
val IPlayer.primaryId: Int?
    get() {
        val id = when {
            this is Player -> namedTag.getInt(PlayersID.NBT_PRIMARY_KEY, -1)
            else -> server.getOfflinePlayerData(uniqueId, false).getInt(PlayersID.NBT_PRIMARY_KEY, -1)
        }
        return if (id < 0) null else id
    }

/**
 * DiscordのID
 * 何も設定してなかったら""を返す
 */
val IPlayer.discordId: String
    get() {
        val id = when {
            this is Player -> namedTag.getString(PlayersID.NBT_DISCORD_ID, "")
            else -> server.getOfflinePlayerData(uniqueId, false).getString(PlayersID.NBT_DISCORD_ID, "")
        }
        return id
    }
