package dev.miubridge.phone

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One ADB transport message: a 24-byte little-endian header (command, arg0, arg1, data length,
 * data checksum, command xor 0xffffffff) followed by the payload.
 */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command).putInt(arg0).putInt(arg1)
        .putInt(data.size).putInt(checksum(data)).putInt(command.inv())
        .put(data)
        .array()

    override fun toString() = "AdbMessage(${name(command)}, $arg0, $arg1, ${data.size} bytes)"

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        const val STLS_VERSION = 0x01000000

        /** We advertise the original protocol version, so we always send real checksums. */
        const val VERSION = 0x01000000
        const val MAX_DATA = 256 * 1024
        const val HEADER_SIZE = 24

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        /** Sum of the payload's unsigned bytes, as adbd computes it for protocol versions before skip-checksum. */
        fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += b.toInt() and 0xff
            return sum
        }

        /**
         * Reads one message. The checksum is not verified: newer adbd sends 0 once it may skip it,
         * and TCP already guards integrity.
         */
        fun read(input: DataInputStream, maxData: Int = MAX_DATA): AdbMessage {
            val header = ByteArray(HEADER_SIZE)
            input.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val length = buf.int
            buf.int // checksum
            val magic = buf.int
            if (magic != command.inv()) throw IOException("bad adb header magic")
            if (length < 0 || length > maxData) throw IOException("adb payload too large: $length")
            val data = ByteArray(length)
            input.readFully(data)
            return AdbMessage(command, arg0, arg1, data)
        }

        private fun name(command: Int) = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(), Charsets.US_ASCII)
    }
}
