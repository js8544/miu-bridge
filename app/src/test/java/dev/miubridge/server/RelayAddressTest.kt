package dev.miubridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayAddressTest {
    private val hosted = "https://hosted.example"

    @Test fun blankMeansTheHostedRelay() {
        assertEquals(hosted, Account.relayAddress("  ", hosted))
    }

    @Test fun acceptsAnOriginAndDropsTheTrailingSlash() {
        assertEquals("https://relay.example.com", Account.relayAddress(" https://relay.example.com/ ", hosted))
        assertEquals("http://192.168.1.5:8787", Account.relayAddress("http://192.168.1.5:8787", hosted))
    }

    @Test fun refusesWhatIsNotAnOrigin() {
        assertNull(Account.relayAddress("ftp://relay.example.com", hosted))
        assertNull(Account.relayAddress("relay.example.com", hosted))
        assertNull(Account.relayAddress("https://relay.example.com/d/abc/mcp", hosted))
        assertNull(Account.relayAddress("https://relay.example.com?x=1", hosted))
    }
}
