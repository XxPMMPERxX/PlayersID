package jp.asteria.player

import org.powernukkitx.IPlayer
import org.powernukkitx.Player
import org.powernukkitx.event.EventHandler
import org.powernukkitx.event.EventPriority
import org.powernukkitx.event.Listener
import org.powernukkitx.event.player.PlayerJoinEvent
import org.powernukkitx.plugin.PluginBase
import jp.asteria.dbconnector.Database
import jp.asteria.player.infrastructure.PlayersTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class PlayersID : PluginBase(), Listener {
    companion object {
        internal const val NBT_PRIMARY_KEY = "jp.asteria.player.PlayersID.PrimaryKey"
        internal const val NBT_DISCORD_ID = "jp.asteria.player.PlayersID.DiscordId"
    }

    override fun onEnable() {
        jp.asteria.player.infrastructure.PlayerTransactions.initialize()
        try {
            ExposedDatabase.connect(Database.getDataSource())
            transaction {
                SchemaUtils.create(PlayersTable)
            }
            jp.asteria.player.infrastructure.PlayerCreations.initializeSchema()
            server.pluginManager.registerEvents(this, this)
        } catch (failure: Throwable) {
            jp.asteria.player.infrastructure.PlayerTransactions.stop()
            throw failure
        }
    }

    override fun onDisable() {
        jp.asteria.player.infrastructure.PlayerTransactions.stop()
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onLogin(event: PlayerJoinEvent) {
        loadPlayerData(event.player)
    }

    private fun loadPlayerData(player: Player) {
        check(server.isPrimaryThread) { "Player login must run on the server primary thread" }
        check(org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.currentOrNull() == null) {
            "Player login must run outside Exposed transactions"
        }
        val identity = jp.asteria.player.infrastructure.PlayerIdentityStore.load(player.xuid, player.name)
        player.nbt.putInt(NBT_PRIMARY_KEY, identity.playerId)
        player.nbt.putString(NBT_DISCORD_ID, identity.discordId ?: "")
    }

}

/**
 * DBのプライマリーキー
 */
val IPlayer.primaryId: Int?
    get() {
        val id = when {
            this is Player -> nbt?.getInt(PlayersID.NBT_PRIMARY_KEY, -1) ?: -1
            else -> server.getOfflinePlayerData(uniqueId, false)?.getInt(PlayersID.NBT_PRIMARY_KEY, -1) ?: -1
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
            this is Player -> nbt?.getString(PlayersID.NBT_DISCORD_ID, "") ?: ""
            else -> server.getOfflinePlayerData(uniqueId, false)?.getString(PlayersID.NBT_DISCORD_ID, "") ?: ""
        }
        return id
    }
