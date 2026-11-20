package dev.miubridge.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayShapeTest {
    @Test
    fun aPhoneKeepsItsOwnScreen() {
        assertEquals(Triple(1080, 2400, 420), PhoneController.shapeFor(1080, 2400, 420))
        assertEquals(Triple(1440, 3120, 560), PhoneController.shapeFor(1440, 3120, 560))
        assertEquals(Triple(1080, 1920, 480), PhoneController.shapeFor(1080, 1920, 480))
    }

    @Test
    fun aPhoneHeldSidewaysIsStillUpright() {
        assertEquals(Triple(1080, 2400, 420), PhoneController.shapeFor(2400, 1080, 420))
    }

    @Test
    fun aWideScreenGetsAnUprightPhoneColumn() {
        // A tablet and an unfolded foldable: full height, phone width, own density.
        assertEquals(Triple(1152, 2560, 320), PhoneController.shapeFor(2560, 1600, 320))
        assertEquals(Triple(992, 2208, 420), PhoneController.shapeFor(1840, 2208, 420))
    }
}
