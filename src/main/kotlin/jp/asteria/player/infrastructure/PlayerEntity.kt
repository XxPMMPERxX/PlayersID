package jp.asteria.player.infrastructure

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

class PlayerEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PlayerEntity>(PlayersTable)

    var xuid by PlayersTable.xuid
    var discordId by PlayersTable.discordId
    var gamerTag by PlayersTable.gamerTag
    var createdAt by PlayersTable.createdAt
    var updatedAt by PlayersTable.updatedAt
}
