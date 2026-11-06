package ru.ravel.netnotepad.note

import java.time.Duration
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
	/** Hashed browser key of the creator, see `ClientKeyFilter`. */
	val ownerKey: String? = null,
	val stopwatch: Stopwatch = Stopwatch(),
) {
	companion object {
		fun empty(id: String) = Note(id, NoteIds.generate(), "", Syntax.PLAIN, false)
	}
}

/** Stopwatch shared by everyone in the note; [startedAt] is set while it runs. */
data class Stopwatch(
	val elapsed: Duration = Duration.ZERO,
	val startedAt: Instant? = null,
) {
	val running: Boolean get() = startedAt != null

	fun elapsedAt(now: Instant): Duration =
		elapsed + (startedAt?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO)

	fun start(now: Instant) = if (running) this else copy(startedAt = now)

	fun pause(now: Instant) = if (running) Stopwatch(elapsedAt(now)) else this
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
