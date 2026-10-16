package jp.asteria.player.infrastructure

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class PlayerCreationsTest {
    private fun fixture(action: () -> Unit) {
        val path = Files.createTempFile("player-creations-", ".db")
        val db = Database.connect("jdbc:sqlite:$path", "org.sqlite.JDBC")
        PlayerTransactions.initialize()
        try {
            transaction { SchemaUtils.create(PlayersTable) }
            PlayerCreations.initializeSchema()
            action()
        } finally {
            PlayerTransactions.stop()
            TransactionManager.closeAndUnregister(db)
            Files.deleteIfExists(path)
        }
    }
    private fun count(table: String): Int = transaction {
        exec("SELECT COUNT(*) FROM $table") { it.next(); it.getInt(1) }!!
    }
    private fun proof(id: Int) = transaction { PlayerCreations.findInCurrentTransaction(id) }

    @Test fun newIdentityKeepsItsCreationReceiptAcrossLoginsAndRenames() = fixture {
        val first = PlayerIdentityStore.load("12345", "first")
        val created = assertNotNull(proof(first.playerId))
        val again = PlayerIdentityStore.load("12345", "renamed")
        assertEquals(first.playerId, again.playerId)
        assertEquals(created.operationId, proof(first.playerId)?.operationId)
        assertEquals(1, count("player_identity_creations_v1"))
        assertEquals(1, count("persistence_operations"))
        transaction {
            exec("SELECT gamertag FROM players") { it.next(); assertEquals("renamed", it.getString(1)) }
        }
    }

    @Test fun legacyIdentityAndUnknownIdentityNeverReceiveNewCreationProof() = fixture {
        val id = transaction { PlayerEntity.new { xuid = "old"; gamerTag = "old" }.id.value }
        assertNull(proof(id))
        assertNull(proof(id + 100))
        assertEquals(id, PlayerIdentityStore.load("old", "updated").playerId)
        assertNull(proof(id))
        assertEquals(0, count("player_identity_creations_v1"))
        assertEquals(0, count("persistence_operations"))
    }

    @Test fun identityAndReceiptRollbackTogether() = fixture {
        assertFailsWith<IllegalStateException> {
            transaction {
                maxAttempts = 1
                val id = PlayerEntity.new { xuid = "rollback"; gamerTag = "before" }.id.value
                PlayerTransactions.write(id) { PlayerCreations.recordInsertedIdentity(id, "rollback") }
                error("after-create")
            }
        }
        assertEquals(0, count("players"))
        assertEquals(0, count("player_identity_creations_v1"))
        assertEquals(0, count("persistence_operations"))
        assertNotNull(proof(PlayerIdentityStore.load("rollback", "retry").playerId))
    }

    @Test fun receiptFailureRollsBackIdentityInsert() = fixture {
        transaction { exec("CREATE TRIGGER reject_receipt BEFORE INSERT ON player_identity_creations_v1 BEGIN SELECT RAISE(ABORT,'test receipt failure'); END") }
        assertFails { PlayerIdentityStore.load("failed", "failed") }
        assertEquals(0, count("players"))
        assertEquals(0, count("persistence_operations"))
    }

    @Test fun corruptReceiptNeverBecomesMissingOrNewIdentity() = fixture {
        val id = PlayerIdentityStore.load("corrupt", "actor").playerId
        transaction { exec("UPDATE persistence_operations SET resultHash='broken'") }
        assertFailsWith<IllegalArgumentException> { proof(id) }
        assertEquals(1, count("players"))
        assertEquals(1, count("player_identity_creations_v1"))
    }

    @Test fun identityMismatchAndMissingReceiptAreHeld() = fixture {
        val id = PlayerIdentityStore.load("identity", "actor").playerId
        transaction { exec("UPDATE players SET xuid='different'") }
        assertFailsWith<IllegalStateException> { proof(id) }
        transaction { exec("UPDATE players SET xuid='identity'"); exec("DELETE FROM persistence_operations") }
        assertFailsWith<IllegalStateException> { proof(id) }
    }

    @Test fun callersMustUseAnOuterTransaction() = fixture {
        assertFailsWith<IllegalStateException> { PlayerCreations.findInCurrentTransaction(1) }
    }
}
