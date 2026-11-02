package ru.ravel.netnotepad.note

import java.security.SecureRandom

object NoteIds {
	private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	private const val LENGTH = 10
	private val random = SecureRandom()

	val PATTERN = Regex("[A-Za-z0-9_-]{4,32}")

	fun generate(): String = buildString(LENGTH) {
		repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
	}

	fun isValid(id: String) = PATTERN.matches(id)
}
