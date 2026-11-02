package ru.ravel.netnotepad.note

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordsTest {

	@Test
	fun `verifies hashed password`() {
		val hash = Passwords.hash("пароль")
		assertTrue(Passwords.verify("пароль", hash))
		assertFalse(Passwords.verify("пароль1", hash))
		assertFalse(Passwords.verify("пароль", "garbage"))
	}

	@Test
	fun `uses a random salt`() {
		assertNotEquals(Passwords.hash("same"), Passwords.hash("same"))
	}
}
