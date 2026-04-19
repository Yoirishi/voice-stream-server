package ru.voicestream.auth

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@ApplicationScoped
class PasswordHashService {
    @ConfigProperty(name = "voice-stream.auth.password-hash-iterations")
    var iterations: Int = 210_000

    private val secureRandom = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val digest = pbkdf2(password, salt, iterations)
        return listOf(ALGORITHM_ID, iterations.toString(), base64Url(salt), base64Url(digest)).joinToString("$")
    }

    fun verify(password: String, encodedHash: String): Boolean {
        val parts = encodedHash.split("$")
        if (parts.size != 4 || parts[0] != ALGORITHM_ID) {
            return false
        }

        val parsedIterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expectedDigest = runCatching { Base64.getUrlDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actualDigest = pbkdf2(password, salt, parsedIterations)

        return MessageDigest.isEqual(expectedDigest, actualDigest)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private const val ALGORITHM_ID = "pbkdf2-sha256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}
