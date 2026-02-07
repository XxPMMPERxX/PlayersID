package jp.asteria.player.infrastructure

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object PlayersTable : IntIdTable("players") {
    val xuid = varchar("xuid", 20).uniqueIndex()
    val discordId = varchar("discord_id", 255).uniqueIndex().nullable()
    val gamerTag = varchar("gamertag", 255)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}
