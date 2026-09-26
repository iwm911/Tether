package app.tether.data

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure-JVM SSH key helpers (no Android dependencies, so they are unit-testable):
 * Ed25519 generation in OpenSSH `openssh-key-v1` format, private-key import via sshj,
 * public-key lines and `SHA256:` fingerprints.
 */
object SshKeyCodec {

    /** A freshly generated key pair, already encoded. */
    data class GeneratedKey(
        /** `-----BEGIN OPENSSH PRIVATE KEY-----` block (unencrypted). */
        val privateKeyPem: String,
        /** `ssh-ed25519 AAAA… comment` */
        val publicKeyLine: String,
        val fingerprint: String,
        /** Raw 32-byte Ed25519 public key. */
        val rawPublicKey: ByteArray,
    )

    /** Result of parsing an imported private key. */
    data class ParsedKey(
        /** "ed25519", "rsa", "ecdsa" (or the raw SSH type name for anything else). */
        val algorithm: String,
        val publicKeyLine: String,
        val fingerprint: String,
        /** True when the key is encrypted and the passphrase was needed to open it. */
        val encrypted: Boolean,
        /** Normalised private key text to persist. */
        val normalizedText: String,
    )

    private const val AUTH_MAGIC = "openssh-key-v1"
    private const val ED25519 = "ssh-ed25519"
    private const val PEM_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val PEM_END = "-----END OPENSSH PRIVATE KEY-----"

    private val random = SecureRandom()

    // ───────────────────────────── generation ─────────────────────────────

    fun generateEd25519(comment: String): GeneratedKey {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(random))
        val pair = gen.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub = pair.public as Ed25519PublicKeyParameters
        return encodeEd25519(priv.encoded, pub.encoded, comment)
    }

    /** Encodes a raw Ed25519 seed (32 bytes) + public key (32 bytes) as an unencrypted openssh-key-v1 file. */
    fun encodeEd25519(seed: ByteArray, publicKey: ByteArray, comment: String): GeneratedKey {
        require(seed.size == 32 && publicKey.size == 32) { "Ed25519 keys are 32 bytes" }
        val cleanComment = comment.replace(Regex("[\\r\\n]"), " ").trim()
        val pubBlob = sshBytes {
            string(ED25519.toByteArray(Charsets.US_ASCII))
            string(publicKey)
        }

        val checkInt = random.nextInt()
        val privateSection = sshBytes {
            int(checkInt)
            int(checkInt)
            string(ED25519.toByteArray(Charsets.US_ASCII))
            string(publicKey)
            string(seed + publicKey) // OpenSSH stores the 64-byte "private" = seed || public
            string(cleanComment.toByteArray(Charsets.UTF_8))
        }.let { section ->
            // Pad to the cipher block size (8 for "none") with bytes 1, 2, 3, …
            val padLen = (8 - section.size % 8) % 8
            section + ByteArray(padLen) { (it + 1).toByte() }
        }

        val file = sshBytes {
            raw(AUTH_MAGIC.toByteArray(Charsets.US_ASCII))
            raw(byteArrayOf(0))
            string("none".toByteArray(Charsets.US_ASCII)) // cipher
            string("none".toByteArray(Charsets.US_ASCII)) // kdf
            string(ByteArray(0)) // kdf options
            int(1) // number of keys
            string(pubBlob)
            string(privateSection)
        }

        val b64 = Base64.getEncoder().encodeToString(file)
        val pem = buildString {
            append(PEM_BEGIN).append('\n')
            b64.chunked(70).forEach { append(it).append('\n') }
            append(PEM_END).append('\n')
        }
        val line = buildString {
            append(ED25519).append(' ').append(Base64.getEncoder().encodeToString(pubBlob))
            if (cleanComment.isNotEmpty()) append(' ').append(cleanComment)
        }
        return GeneratedKey(pem, line, fingerprint(pubBlob), publicKey.copyOf())
    }

    // ───────────────────────────── import ─────────────────────────────

    /**
     * Parses a private key (OpenSSH v1, PEM/PKCS#8, PuTTY) with sshj.
     * @throws IllegalArgumentException with a user-facing message.
     */
    fun parsePrivateKey(text: String, passphrase: String?, comment: String): ParsedKey {
        val normalized = normalize(text)
        if (normalized.isBlank()) throw IllegalArgumentException("Paste a private key first")
        val firstLine = normalized.lineSequence().first().trim()
        if (PUBLIC_LINE.matches(firstLine) && !normalized.contains("PRIVATE KEY")) {
            throw IllegalArgumentException("That's a public key — paste or pick the private key instead")
        }
        val format = try {
            KeyProviderUtil.detectKeyFileFormat(normalized, false)
        } catch (e: Exception) {
            KeyFormat.Unknown
        }
        val provider: FileKeyProvider = when (format) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.PKCS8, KeyFormat.OpenSSH -> PKCS8KeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            else -> throw IllegalArgumentException("Unrecognised key format")
        }
        val finder = RecordingPasswordFinder(passphrase?.takeIf { it.isNotEmpty() })
        provider.init(StringReader(normalized), finder)
        val pub: PublicKey = try {
            provider.private // forces decryption / full parse
            provider.public
        } catch (e: Exception) {
            throw when {
                finder.asked && finder.passphrase == null -> IllegalArgumentException("That key needs a passphrase")
                finder.asked -> IllegalArgumentException("Wrong passphrase for that key")
                isPassphraseError(e) && finder.passphrase == null -> IllegalArgumentException("That key needs a passphrase")
                else -> IllegalArgumentException("Unrecognised key format", e)
            }
        } ?: throw IllegalArgumentException("Unrecognised key format")

        val type = KeyType.fromKey(pub)
        if (type == KeyType.UNKNOWN) throw IllegalArgumentException("Unsupported key type")
        return ParsedKey(
            algorithm = algorithmName(type.toString()),
            publicKeyLine = publicKeyLine(pub, comment),
            fingerprint = fingerprint(pub),
            encrypted = finder.asked,
            normalizedText = normalized,
        )
    }

    private fun isPassphraseError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = (t.message ?: "") + " " + t.javaClass.simpleName
            if (m.contains("passphrase", true) || m.contains("password", true) || m.contains("Decryption", true) || m.contains("encrypted", true)) return true
            t = t.cause
        }
        return false
    }

    private class RecordingPasswordFinder(val passphrase: String?) : PasswordFinder {
        @Volatile var asked = false
        override fun reqPassword(resource: Resource<*>?): CharArray? {
            asked = true
            return passphrase?.toCharArray()
        }
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    // ───────────────────────────── public keys & fingerprints ─────────────────────────────

    /** SSH wire-format blob of a public key. */
    fun publicKeyBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    /** `ssh-ed25519 AAAA… comment` */
    fun publicKeyLine(key: PublicKey, comment: String): String {
        val clean = comment.replace(Regex("[\\r\\n]"), " ").trim()
        val base = KeyType.fromKey(key).toString() + " " + Base64.getEncoder().encodeToString(publicKeyBlob(key))
        return if (clean.isEmpty()) base else "$base $clean"
    }

    fun keyTypeName(key: PublicKey): String = KeyType.fromKey(key).toString()

    fun fingerprint(key: PublicKey): String = fingerprint(publicKeyBlob(key))

    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** "ssh-ed25519" → "ed25519", "ssh-rsa" → "rsa", "ecdsa-sha2-nistp256" → "ecdsa". */
    fun algorithmName(sshType: String): String = when {
        sshType.contains("ed25519") -> "ed25519"
        sshType.contains("rsa") -> "rsa"
        sshType.startsWith("ecdsa") -> "ecdsa"
        sshType.contains("dss") -> "dsa"
        else -> sshType
    }

    /** Trims, unifies line endings and ensures a trailing newline (sshj's PEM readers are picky). */
    fun normalize(text: String): String {
        val t = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        return if (t.isEmpty()) t else t + "\n"
    }

    private val PUBLIC_LINE = Regex("^(ssh-(ed25519|rsa|dss)|ecdsa-sha2-\\S+|sk-\\S+)\\s+[A-Za-z0-9+/=]{16,}.*$")

    // ───────────────────────────── SSH wire helpers ─────────────────────────────

    private class SshWriter(private val out: DataOutputStream) {
        fun int(v: Int) = out.writeInt(v)
        fun raw(b: ByteArray) = out.write(b)
        fun string(b: ByteArray) {
            out.writeInt(b.size)
            out.write(b)
        }
    }

    private inline fun sshBytes(block: SshWriter.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { SshWriter(it).block() }
        return bos.toByteArray()
    }
}
