package dev.miubridge.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

class AdbMessageTest {
    @Test
    fun encodesHeaderLittleEndianWithChecksumAndMagic() {
        val payload = "host::\u0000".toByteArray()
        val bytes = AdbMessage(AdbMessage.CNXN, 0x01000000, 4096, payload).encode()
        assertEquals(24 + payload.size, bytes.size)
        // "CNXN" on the wire, then version 0x01000000 and maxdata 4096 little-endian
        assertArrayEquals("CNXN".toByteArray(), bytes.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), bytes.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0, 0x10, 0, 0), bytes.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(7, 0, 0, 0), bytes.copyOfRange(12, 16))
        // 'h'+'o'+'s'+'t'+':'+':'+0 = 104+111+115+116+58+58 = 562 = 0x0232
        assertArrayEquals(byteArrayOf(0x32, 0x02, 0, 0), bytes.copyOfRange(16, 20))
        // magic = command xor 0xffffffff
        assertArrayEquals(byteArrayOf(0xbc.toByte(), 0xb1.toByte(), 0xa7.toByte(), 0xb1.toByte()), bytes.copyOfRange(20, 24))
    }

    @Test
    fun checksumTreatsBytesAsUnsigned() {
        assertEquals(255 + 128 + 1, AdbMessage.checksum(byteArrayOf(-1, -128, 1)))
    }

    @Test
    fun roundTrips() {
        val original = AdbMessage(AdbMessage.WRTE, 7, 3, byteArrayOf(1, 2, 3, -4))
        val read = AdbMessage.read(DataInputStream(ByteArrayInputStream(original.encode())))
        assertEquals(AdbMessage.WRTE, read.command)
        assertEquals(7, read.arg0)
        assertEquals(3, read.arg1)
        assertArrayEquals(original.data, read.data)
    }

    @Test
    fun acceptsZeroChecksumFromNewerAdbd() {
        val bytes = AdbMessage(AdbMessage.OKAY, 1, 2, byteArrayOf(9)).encode()
        for (i in 16 until 20) bytes[i] = 0
        assertEquals(AdbMessage.OKAY, AdbMessage.read(DataInputStream(ByteArrayInputStream(bytes))).command)
    }

    @Test(expected = IOException::class)
    fun rejectsBadMagic() {
        val bytes = AdbMessage(AdbMessage.OKAY, 1, 2).encode()
        bytes[20] = 0
        AdbMessage.read(DataInputStream(ByteArrayInputStream(bytes)))
    }

    @Test(expected = IOException::class)
    fun rejectsOversizedPayload() {
        val bytes = AdbMessage(AdbMessage.WRTE, 1, 2, ByteArray(100)).encode()
        AdbMessage.read(DataInputStream(ByteArrayInputStream(bytes)), maxData = 10)
    }
}
