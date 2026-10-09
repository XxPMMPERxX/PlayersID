package jp.asteria.player

import org.powernukkitx.IPlayer
import org.powernukkitx.Player
import org.powernukkitx.event.EventHandler
import org.powernukkitx.event.EventPriority
import org.powernukkitx.event.Listener
import org.powernukkitx.event.player.PlayerJoinEvent
import org.powernukkitx.plugin.PluginBase
import jp.asteria.dbconnector.Database
import jp.asteria.player.infrastructure.PlayerEntity
import jp.asteria.player.infrastructure.PlayersTable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class PlayersID : PluginBase(), Listener {
    companion object {
        internal const val NBT_PRIMARY_KEY = "jp.asteria.player.PlayersID.PrimaryKey"
        internal const val NBT_DISCORD_ID = "jp.asteria.player.PlayersID.DiscordId"
    }

    override fun onEnable() {
        ExposedDatabase.connect(Database.getDataSource())
        transaction {
            SchemaUtils.create(PlayersTable)
        }

        server.pluginManager.registerEvents(this, this)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onLogin(event: PlayerJoinEvent) {
        loadPlayerData(event.player)
    }

    private fun loadPlayerData(player: Player) {
        transaction {
            var playerData = PlayerEntity.find { PlayersTable.xuid eq player.xuid }.firstOrNull()
            // 存在する場合はゲーマータグ更新
            // 存在しない場合は新規作成
            if (playerData != null) {
                playerData.gamerTag = player.name
                playerData.updatedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            } else {
                playerData = PlayerEntity.new {
                    xuid = player.xuid
                    gamerTag = player.name
                }
            }

            player.nbt.putInt(NBT_PRIMARY_KEY, playerData.id.value)
            player.nbt.putString(NBT_DISCORD_ID, playerData.discordId ?: "")
        }
    }
}

/**
 * DBのプライマリーキー
 */
val IPlayer.primaryId: Int?
    get() {
        val id = when {
            this is Player -> nbt.getInt(PlayersID.NBT_PRIMARY_KEY, -1)
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
            this is Player -> nbt.getString(PlayersID.NBT_DISCORD_ID, "")
            else -> server.getOfflinePlayerData(uniqueId, false).getString(PlayersID.NBT_DISCORD_ID, "")
        }
        return id
    }
