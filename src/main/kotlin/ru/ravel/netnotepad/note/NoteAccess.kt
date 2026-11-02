package ru.ravel.netnotepad.note

import jakarta.servlet.http.HttpSession
import java.util.concurrent.ConcurrentHashMap

/** Remembers in the HTTP session which password-protected notes the visitor has unlocked. */
object NoteAccess {
	private const val ATTRIBUTE = "netnotepad.unlocked"

	fun isUnlocked(session: HttpSession?, noteId: String): Boolean = try {
		@Suppress("UNCHECKED_CAST")
		(session?.getAttribute(ATTRIBUTE) as? Set<String>)?.contains(noteId) == true
	} catch (_: IllegalStateException) {
		false // invalidated session
	}

	fun unlock(session: HttpSession?, noteId: String) {
		try {
			session ?: return
			synchronized(session) {
				@Suppress("UNCHECKED_CAST")
				val unlocked = session.getAttribute(ATTRIBUTE) as? MutableSet<String>
					?: ConcurrentHashMap.newKeySet<String>().also { session.setAttribute(ATTRIBUTE, it) }
				unlocked += noteId
			}
		} catch (_: IllegalStateException) {
		}
	}
}
