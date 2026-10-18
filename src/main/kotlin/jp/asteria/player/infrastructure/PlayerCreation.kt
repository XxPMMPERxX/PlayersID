package jp.asteria.player.infrastructure

import java.util.UUID

/** 新規IDを作った事実。欠損は旧データの変換や再初期化を許可しない。 */
class PlayerCreation internal constructor(val playerId: Int, val operationId: UUID, val sourceId: String)
