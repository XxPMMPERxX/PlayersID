package jp.asteria.player

import org.powernukkitx.Player
import org.powernukkitx.entity.Entity
import org.powernukkitx.nbt.tag.CompoundTag
import kotlin.test.*

class PlayerIdentityAccessTest {
    private fun player(): Player {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Player::class.java) as Player
    }

    @Test fun uninitializedNativePlayerHasNoIdentityWithoutCreatingNbt() {
        val player = player()
        assertNull(player.primaryId)
        assertEquals("", player.discordId)
        assertNull(player.nbt)
    }

    @Test fun existingNativeValuesAndSentinelBehaviorArePreservedWithoutWriting() {
        val player = player()
        val tag = CompoundTag().putInt(PlayersID.NBT_PRIMARY_KEY, 42).putString(PlayersID.NBT_DISCORD_ID, "stored").putString("unknown", "retained")
        Entity::class.java.getDeclaredField("nbt").apply { isAccessible = true }.set(player, tag)
        assertEquals(42, player.primaryId)
        assertEquals("stored", player.discordId)
        tag.putInt(PlayersID.NBT_PRIMARY_KEY, 0)
        assertEquals(0, player.primaryId)
        tag.putInt(PlayersID.NBT_PRIMARY_KEY, -1)
        assertNull(player.primaryId)
        assertSame(tag, player.nbt)
        assertEquals("retained", tag.getString("unknown"))
    }
}
