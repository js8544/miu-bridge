package dev.miubridge.phone

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateEntry
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.SecureRandom
import java.util.Date

/**
 * TLS 1.3 client towards adbd, as wireless debugging needs it (pairing and the STLS upgrade).
 * We present a self-signed certificate for our adb RSA key; adbd authorizes by that public key.
 * adbd's own certificate is random and unverifiable, so we accept it: pairing is authenticated by
 * SPAKE2 over the exported key material, and connections only carry what our own key may do.
 */
internal class AdbTls private constructor(private val protocol: TlsClientProtocol, private val client: Client) {
    val input: InputStream get() = protocol.inputStream
    val output: OutputStream get() = protocol.outputStream

    /**
     * adb's RFC 8446 exporter output (label "adb-label\0", no context, 64 bytes), as BoringSSL's
     * SSL_export_keying_material. BouncyCastle only allows exporting while the handshake completes.
     */
    val exportedKeyMaterial: ByteArray get() = client.exported ?: throw IllegalStateException("handshake not complete")

    fun close() = runCatching { protocol.close() }

    private class Client(crypto: BcTlsCrypto, private val key: RSAPrivateCrtKeyParameters, private val cert: TlsCertificate) : DefaultTlsClient(crypto) {
        @Volatile var exported: ByteArray? = null

        override fun notifyHandshakeComplete() {
            super.notifyHandshakeComplete()
            exported = context.exportKeyingMaterial("adb-label\u0000", null, 64)
        }

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

        override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) = Unit

            override fun getClientCredentials(request: CertificateRequest): TlsCredentials {
                // TLS 1.3 echoes the server's request context in our Certificate message.
                val chain = Certificate(request.certificateRequestContext, arrayOf(CertificateEntry(cert, null)))
                return BcDefaultTlsCredentialedSigner(
                    TlsCryptoParameters(context), crypto as BcTlsCrypto, key, chain,
                    SignatureAndHashAlgorithm.rsa_pss_rsae_sha256,
                )
            }
        }
    }

    companion object {
        fun handshake(socket: Socket, key: RSAPrivateCrtKeyParameters): AdbTls = handshake(socket.getInputStream(), socket.getOutputStream(), key)

        fun handshake(input: InputStream, output: OutputStream, key: RSAPrivateCrtKeyParameters): AdbTls {
            val crypto = BcTlsCrypto(SecureRandom())
            val cert = crypto.createCertificate(selfSignedCertificate(key))
            val client = Client(crypto, key, cert)
            val protocol = TlsClientProtocol(input, output)
            protocol.connect(client)
            return AdbTls(protocol, client)
        }

        /** Like adb's own: CN=Adb, O=Android, C=US, ten years, CA with key-signing usage. */
        fun selfSignedCertificate(key: RSAPrivateCrtKeyParameters): ByteArray {
            val public = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(RSAKeyParameters(false, key.modulus, key.publicExponent))
            val name = X500Name("C=US,O=Android,CN=Adb")
            val now = System.currentTimeMillis()
            val builder = X509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L), name, public)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature))
                .addExtension(Extension.subjectKeyIdentifier, false, BcX509ExtensionUtils().createSubjectKeyIdentifier(public))
            val signatureAlgorithm = DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withRSA")
            val digestAlgorithm = DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm)
            return builder.build(BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm).build(key)).encoded
        }
    }
}
