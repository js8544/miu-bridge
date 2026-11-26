package dev.miubridge.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiskyAppsTest {
    private val risky = setOf("bank", "pay")

    @Test fun firstStartBlocksEveryRiskyAppAndKeepsTheOwnersList() {
        val (blocked, offered) = RiskyApps.addNew(setOf("game"), emptySet(), risky)!!
        assertEquals(setOf("game", "bank", "pay"), blocked)
        assertEquals(risky, offered)
    }

    @Test fun anAppTheOwnerTookOffStaysOff() {
        assertNull(RiskyApps.addNew(setOf("pay"), risky, risky))
    }

    @Test fun aPackageAddedLaterIsBlockedOnce() {
        val (blocked, offered) = RiskyApps.addNew(setOf("pay"), risky, risky + "broker")!!
        assertEquals(setOf("pay", "broker"), blocked)
        assertEquals(risky + "broker", offered)
    }
}
