package dev.miubridge.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator

class AdbKeyTest {
    @Test fun aSavedKeyIsLoadedBackNotReplaced() {
        val file = File.createTempFile("adbkey", ".pk8").also { it.delete() }
        val first = AdbKey.loadOrCreate(file)
        val bytes = file.readBytes()
        val second = AdbKey.loadOrCreate(file)
        assertEquals(first.key.modulus, second.key.modulus)
        assertArrayEquals(bytes, file.readBytes())
        file.delete()
    }

    @Test fun readsKeysWrittenByThePlatformGenerator() {
        // Earlier versions stored the platform generator's PKCS#8; those files must keep working.
        val file = File.createTempFile("adbkey", ".pk8")
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = gen.generateKeyPair()
        file.writeBytes(pair.private.encoded)
        val key = AdbKey.loadOrCreate(file)
        assertEquals((pair.public as java.security.interfaces.RSAPublicKey).modulus, key.key.modulus)
        assertArrayEquals(pair.private.encoded, file.readBytes())
        file.delete()
    }

    @Test fun certificateBuildsFromTheKey() {
        val file = File.createTempFile("adbkey", ".pk8").also { it.delete() }
        val der = AdbTls.selfSignedCertificate(AdbKey.loadOrCreate(file).key)
        assertEquals(0x30, der[0].toInt())
        file.delete()
    }
}
