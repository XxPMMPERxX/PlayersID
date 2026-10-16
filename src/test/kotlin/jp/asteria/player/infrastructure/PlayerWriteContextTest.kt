package jp.asteria.player.infrastructure

import jp.asteria.dbconnector.SqlOperationLedger
import jp.asteria.dbconnector.CanonicalRequest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PlayerWriteContextTest {
    private fun fixture(action: () -> Unit) {
        val path = Files.createTempFile("player-context-", ".db")
        val db = Database.connect("jdbc:sqlite:$path", "org.sqlite.JDBC")
        PlayerTransactions.initialize()
        try {
            transaction {
                exec("CREATE TABLE players (id INTEGER PRIMARY KEY)")
                exec("INSERT INTO players VALUES (1),(2)")
                exec("CREATE TABLE account (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)")
                exec("INSERT INTO account VALUES (1,10),(2,20)")
            }
            SqlOperationLedger.initializeSchema()
            action()
        } finally {
            PlayerTransactions.stop()
            TransactionManager.closeAndUnregister(db)
            Files.deleteIfExists(path)
        }
    }
    private fun debit() { TransactionManager.current().exec("UPDATE account SET amount=amount-1 WHERE id=1") }
    private fun amount() = transaction { exec("SELECT amount FROM account WHERE id=1") { it.next(); it.getInt(1) }!! }

    @Test fun participantsShareOneConnectionAndImmutableSortedLockSet() = fixture {
        val result = PlayerTransactions.writeClosed(listOf(2, 1, 2)) { context ->
            assertEquals(listOf(1, 2), context.playerIds)
            assertFailsWith<UnsupportedOperationException> { (context.playerIds as MutableList).add(3) }
            val first = TransactionManager.current().connection.connection
            context.enlist(1) { assertSame(first, TransactionManager.current().connection.connection); debit() }
            PlayerTransactions.write(2) { assertSame(first, TransactionManager.current().connection.connection) }
            42
        }
        assertEquals(42, result)
        assertEquals(9, amount())
    }

    @Test fun closedContextRejectsReuseEvenInAnotherTransaction() = fixture {
        val context = PlayerTransactions.writeClosed(listOf(1)) { it }
        assertFailsWith<IllegalStateException> { context.enlist(1) { debit() } }
        transaction { assertFailsWith<IllegalStateException> { context.enlist(1) { debit() } } }
        assertEquals(10, amount())
    }

    @Test fun caughtParticipantExpansionRollsBackAllPriorMutationsAndReceipt() = fixture {
        val operation = UUID.randomUUID()
        assertFailsWith<IllegalStateException> {
            PlayerTransactions.writeClosed(listOf(1)) { context ->
                SqlOperationLedger.enlist("test", operation, "context/$operation", CanonicalRequest(mapOf("amount" to 1)),
                    listOf(1), emptyMap()) {
                    context.enlist(1) { debit() }
                    assertFailsWith<IllegalArgumentException> { context.enlist(2) { error("unlisted player") } }
                    SqlOperationLedger.Outcome(SqlOperationLedger.State.COMMITTED, byteArrayOf(1))
                }
            }
        }
        assertEquals(10, amount())
        transaction { assertNull(SqlOperationLedger.query("test", operation)) }
    }

    @Test fun legacyWriteCannotExpandTheClosedSetEvenIfFailureIsCaught() = fixture {
        assertFailsWith<IllegalStateException> {
            PlayerTransactions.writeClosed(listOf(1)) { context ->
                context.enlist(1) { debit() }
                assertFailsWith<IllegalArgumentException> { PlayerTransactions.write(2) { error("new player") } }
            }
        }
        assertEquals(10, amount())
    }

    @Test fun anotherThreadCannotUseTheContextAndPoisonsTheRoot() = fixture {
        val failure = AtomicReference<Throwable?>()
        assertFailsWith<IllegalStateException> {
            PlayerTransactions.writeClosed(listOf(1)) { context ->
                context.enlist(1) { debit() }
                val thread = Thread {
                    try { context.enlist(1) { error("wrong thread callback") } }
                    catch (error: Throwable) { failure.set(error) }
                }
                thread.start(); thread.join()
                assertIs<IllegalStateException>(failure.get())
            }
        }
        assertEquals(10, amount())
    }

    @Test fun caughtNestedRootDoesNotCommitItsParentsWrites() = fixture {
        assertFailsWith<IllegalStateException> {
            PlayerTransactions.writeClosed(listOf(1)) { context ->
                context.enlist(1) { debit() }
                assertFailsWith<IllegalStateException> { PlayerTransactions.writeClosed(listOf(1)) { 0 } }
            }
        }
        assertEquals(10, amount())
    }

    @Test fun anotherDatabaseConnectionCannotEnlistInTheRoot() = fixture {
        val otherPath = Files.createTempFile("player-context-other-", ".db")
        var other: Database? = null
        try {
            assertFailsWith<IllegalStateException> {
                PlayerTransactions.writeClosed(listOf(1)) { context ->
                    context.enlist(1) { debit() }
                    val secondary = Database.connect("jdbc:sqlite:$otherPath", "org.sqlite.JDBC")
                    other = secondary
                    transaction(secondary) {
                        assertFailsWith<IllegalStateException> { context.enlist(1) { error("different DB") } }
                    }
                }
            }
        } finally {
            other?.let { TransactionManager.closeAndUnregister(it) }
            Files.deleteIfExists(otherPath)
        }
        assertEquals(10, amount())
    }

    @Test fun outerLegacyTransactionCannotBeSilentlyPromotedToClosedContext() = fixture {
        transaction {
            assertFailsWith<IllegalStateException> { PlayerTransactions.writeClosed(listOf(1)) { error("nested root") } }
        }
        assertEquals(10, amount())
    }

    @Test fun unknownPlayerAndStoppedServiceNeverEnterTheAction() = fixture {
        assertFailsWith<IllegalArgumentException> { PlayerTransactions.writeClosed(listOf(999)) { error("unknown player") } }
        assertFailsWith<IllegalArgumentException> { PlayerTransactions.writeClosed(emptyList()) { error("empty players") } }
        PlayerTransactions.stop()
        assertFailsWith<IllegalStateException> { PlayerTransactions.writeClosed(listOf(1)) { error("stopped") } }
        assertEquals(10, amount())
    }
}
