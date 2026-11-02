package ru.ravel.netnotepad.note

import java.time.Instant

data class Note(
	val id: String,
	/** Id of the read-only link; it does not reveal the editable id. */
	val readId: String,
	val content: String,
	val syntax: Syntax,
	val preview: Boolean,
	val passwordHash: String? = null,
	val expiresAt: Instant? = null,
) {
	companion object {
		fun empty(id: String) = Note(id, NoteIds.generate(), "", Syntax.PLAIN, false)
	}
}

enum class Syntax(val code: String) {
	PLAIN("plain"),
	JAVA("java"),
	KOTLIN("kotlin"),
	JAVASCRIPT("javascript"),
	PYTHON("python"),
	SQL("sql"),
	JSON("json"),
	YAML("yaml"),
	MARKDOWN("markdown");

	companion object {
		fun fromCode(code: String?): Syntax? = entries.firstOrNull { it.code == code }
	}
}

data class NoteVersion(
	val id: Long,
	val content: String,
	val syntax: Syntax,
	val createdAt: Instant,
)
