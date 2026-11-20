package dev.miubridge.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun laterVersionsAreNewer() {
        assertTrue(Updates.isNewer("0.2.0", "0.1.0"))
        assertTrue(Updates.isNewer("v0.1.1", "0.1.0"))
        assertTrue(Updates.isNewer("0.10.0", "0.9.5"))
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertTrue(Updates.isNewer("0.1.0.1", "0.1.0"))
    }

    @Test fun sameOrEarlierVersionsAreNot() {
        assertFalse(Updates.isNewer("0.1.0", "0.1.0"))
        assertFalse(Updates.isNewer("0.1", "0.1.0"))
        assertFalse(Updates.isNewer("0.0.9", "0.1.0"))
        assertFalse(Updates.isNewer("0.1.0-beta", "0.1.0"))
    }

    @Test fun unreadableVersionsAreNot() {
        assertFalse(Updates.isNewer("", "0.1.0"))
        assertFalse(Updates.isNewer("latest", "0.1.0"))
        assertFalse(Updates.isNewer("1.x", "0.1.0"))
    }
}
