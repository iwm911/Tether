package app.tether.data

import com.hierynomus.sshj.signature.SignatureEdDSA
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.password.PasswordFinder
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.security.Security
import java.util.Base64

class KeyGenTest {

    @Before
    fun installProvider() {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    @Test
    fun generatedKeyHasOpenSshShape() {
        val key = SshKeyCodec.generateEd25519("tether@test-device")
        assertTrue(key.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(key.privateKeyPem.endsWith("-----END OPENSSH PRIVATE KEY-----\n"))
        key.privateKeyPem.lines().filter { it.isNotEmpty() }.forEach { assertTrue(it.length <= 70) }

        val parts = key.publicKeyLine.split(' ')
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("tether@test-device", parts[2])
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        assertFalse(key.fingerprint.endsWith("="))
        // SHA-256 → 32 bytes → 43 base64 chars without padding.
        assertEquals(7 + 43, key.fingerprint.length)
    }

    @Test
    fun generatedKeyRoundTripsThroughSshjAndSigns() {
        val key = SshKeyCodec.generateEd25519("tether@test-device")

        val file = OpenSSHKeyV1KeyFile()
        file.init(StringReader(key.privateKeyPem), null as PasswordFinder?)
        val priv = file.private
        val pub = file.public
        assertEquals(KeyType.ED25519, KeyType.fromKey(pub))

        // The public key sshj derives from the private file equals the one we advertised.
        val advertisedBlob = Base64.getDecoder().decode(key.publicKeyLine.split(' ')[1])
        assertArrayEquals(advertisedBlob, SshKeyCodec.publicKeyBlob(pub))
        assertEquals(key.fingerprint, SshKeyCodec.fingerprint(pub))

        // Sign with the private key sshj loaded…
        val data = "tether handshake payload".toByteArray()
        val signer = SignatureEdDSA.Factory().create()
        signer.initSign(priv)
        signer.update(data)
        val rawSig = signer.sign()

        // …verify with sshj using the SSH-encoded signature blob…
        val verifier = SignatureEdDSA.Factory().create()
        verifier.initVerify(pub)
        verifier.update(data)
        val encoded = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(rawSig).compactData
        assertTrue(verifier.verify(encoded))

        // …and independently with BouncyCastle against the raw public key we generated.
        val bc = Ed25519Signer()
        bc.init(false, Ed25519PublicKeyParameters(key.rawPublicKey, 0))
        bc.update(data, 0, data.size)
        assertTrue(bc.verifySignature(rawSig))

        // A tampered message must not verify.
        val bad = Ed25519Signer()
        bad.init(false, Ed25519PublicKeyParameters(key.rawPublicKey, 0))
        val tampered = "tether handshake payloaD".toByteArray()
        bad.update(tampered, 0, tampered.size)
        assertFalse(bad.verifySignature(rawSig))
    }

    @Test
    fun importParsesGeneratedKey() {
        val key = SshKeyCodec.generateEd25519("tether@test-device")
        val parsed = SshKeyCodec.parsePrivateKey(key.privateKeyPem.replace("\n", "\r\n"), null, "imported")
        assertEquals("ed25519", parsed.algorithm)
        assertEquals(key.fingerprint, parsed.fingerprint)
        assertFalse(parsed.encrypted)
        assertEquals(key.publicKeyLine.substringBeforeLast(' ') + " imported", parsed.publicKeyLine)
    }

    @Test
    fun importRejectsGarbageAndPublicKeys() {
        val key = SshKeyCodec.generateEd25519("x")
        try {
            SshKeyCodec.parsePrivateKey("hello world", null, "n")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unrecognised key format", e.message)
        }
        try {
            SshKeyCodec.parsePrivateKey(key.publicKeyLine, null, "n")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("public key"))
        }
    }

    @Test
    fun eachKeyIsUnique() {
        val a = SshKeyCodec.generateEd25519("a")
        val b = SshKeyCodec.generateEd25519("a")
        assertFalse(a.fingerprint == b.fingerprint)
    }
}
