package ru.ravel.netnotepad.note

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 password hashes in the form `pbkdf2$iterations$salt$hash`. */
object Passwords {
	private const val ALGORITHM = "PBKDF2WithHmacSHA256"
	private const val ITERATIONS = 210_000
	private const val KEY_BITS = 256
	private val random = SecureRandom()
	private val encoder = Base64.getEncoder()
	private val decoder = Base64.getDecoder()

	fun hash(password: String): String {
		val salt = ByteArray(16).also(random::nextBytes)
		return "pbkdf2\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(derive(password, salt, ITERATIONS))}"
	}

	fun verify(password: String, hash: String): Boolean {
		val parts = hash.split('$')
		if (parts.size != 4 || parts[0] != "pbkdf2") return false
		val expected = decoder.decode(parts[3])
		return MessageDigest.isEqual(expected, derive(password, decoder.decode(parts[2]), parts[1].toInt()))
	}

	private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
		SecretKeyFactory.getInstance(ALGORITHM)
			.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS))
			.encoded
}
