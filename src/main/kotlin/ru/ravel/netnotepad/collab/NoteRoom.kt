package ru.ravel.netnotepad.collab

import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import ru.ravel.netnotepad.note.Note
import ru.ravel.netnotepad.note.NoteAccess
import ru.ravel.netnotepad.note.Passwords
import ru.ravel.netnotepad.note.Stopwatch
import ru.ravel.netnotepad.note.Syntax
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-memory state of one note shared by all connected participants.
 *
 * Implements the server side of the `@codemirror/collab` protocol: the server keeps an ordered list
 * of updates and accepts a push only if it is based on the latest version. Clients that are behind
 * receive the missing updates, rebase their local changes and push again.
 */
class NoteRoom(
	note: Note,
	/** Whether the note is already stored in the database. */
	private val persisted: Boolean,
	private val maxNoteLength: Int,
	private val maxParticipants: Int,
	private val mapper: JsonMapper,
) {
	val id = note.id
	val readId = note.readId

	/** Identifies this in-memory incarnation; versions are only comparable within one epoch. */
	val epoch: String = UUID.randomUUID().toString()

	private val lock = ReentrantLock()
	private val participants = LinkedHashSet<Participant>()
	private val history = ArrayDeque<Update>()
	private var doc = note.content
	private var syntax = note.syntax
	private var preview = note.preview
	private var passwordHash = note.passwordHash
	private var expiresAt = note.expiresAt
	private var ownerKey = note.ownerKey
	private var stopwatch = note.stopwatch
	private var version = 0
	private var dirty = false
	private var closed = false
	private var idleSince: Instant? = Instant.now()

	data class Update(val clientID: String, val changes: JsonNode)

	enum class JoinResult { JOINED, LOCKED, FULL, CLOSED }

	enum class StopwatchAction { START, PAUSE, RESET }

	val hasPassword: Boolean get() = lock.withLock { passwordHash != null }

	fun join(participant: Participant, clientEpoch: String?, clientVersion: Int?): JoinResult = lock.withLock {
		when {
			closed -> return JoinResult.CLOSED
			passwordHash != null && !NoteAccess.isUnlocked(participant.httpSession, id) -> return JoinResult.LOCKED
			participants.size >= maxParticipants -> return JoinResult.FULL
		}
		participants += participant
		idleSince = null
		// The creator is the first editor to open a new note; notes created before ownership existed are claimed the same way
		if (ownerKey == null && !participant.readOnly && participant.clientKey != null) {
			ownerKey = participant.clientKey
			if (persisted) dirty = true
		}
		val missed = if (clientEpoch == epoch && clientVersion != null) updatesSince(clientVersion) else null
		if (missed != null) {
			send(participant, mapOf("type" to "resume", "updates" to missed))
		} else {
			send(
				participant, mapOf(
					"type" to "init",
					"epoch" to epoch,
					"version" to version,
					"doc" to doc,
					"you" to participant.id,
					"readOnly" to participant.readOnly,
					"owner" to isOwner(participant),
					// The read-only link must not reveal the editable one, and vice versa is fine
					"readId" to if (participant.readOnly) null else readId,
				) + settings() + protection()
			)
		}
		send(participant, stopwatchMessage())
		broadcastPresence()
		JoinResult.JOINED
	}

	fun leave(participant: Participant) = lock.withLock {
		if (participants.remove(participant)) {
			if (participants.isEmpty()) idleSince = Instant.now()
			broadcastPresence()
		}
	}

	fun push(participant: Participant, baseVersion: Int, updates: List<Update>) = lock.withLock {
		checkEditor(participant)
		if (baseVersion != version) {
			// The client has not seen some updates yet; they are already on their way to it.
			send(participant, mapOf("type" to "conflict"))
			return
		}
		var newDoc = doc
		for (update in updates) {
			newDoc = ChangeSetJson.apply(newDoc, update.changes)
		}
		commit(newDoc, updates)
	}

	/** Any editor may change the syntax, only the owner splits the screen for everyone. */
	fun changeSettings(participant: Participant, newSyntax: Syntax, newPreview: Boolean) = lock.withLock {
		checkEditor(participant)
		applySettings(newSyntax, if (isOwner(participant)) newPreview else preview)
	}

	/** Any editor controls the stopwatch; viewers only see it. */
	fun controlStopwatch(participant: Participant, action: StopwatchAction) = lock.withLock {
		checkEditor(participant)
		val now = Instant.now()
		val next = when (action) {
			StopwatchAction.START -> stopwatch.start(now)
			StopwatchAction.PAUSE -> stopwatch.pause(now)
			StopwatchAction.RESET -> Stopwatch()
		}
		if (next != stopwatch) {
			stopwatch = next
			dirty = true
		}
		// Sent even without a change, so that the sender drops its optimistic state
		broadcast(stopwatchMessage())
	}

	fun changeProfile(participant: Participant, name: String, color: String) = lock.withLock {
		participant.name = name
		participant.color = color
		broadcastPresence()
	}

	/** Relays the selection of a participant; positions refer to the document at [baseVersion]. */
	fun moveCursor(participant: Participant, baseVersion: Int, ranges: List<IntArray>) = lock.withLock {
		val message = TextMessage(
			mapper.writeValueAsString(
				mapOf("type" to "cursor", "id" to participant.id, "version" to baseVersion, "ranges" to ranges)
			)
		)
		participants.filter { it !== participant }.forEach { send(it, message) }
	}

	/**
	 * @param passwordHash new hash, `""` to remove the password, `null` to keep it
	 * @param ttl new lifetime, [Duration.ZERO] for unlimited, `null` to keep it
	 */
	fun protect(participant: Participant, passwordHash: String?, ttl: Duration?) = lock.withLock {
		checkOwner(participant)
		if (passwordHash != null) {
			this.passwordHash = passwordHash.ifEmpty { null }
			// Whoever is already in keeps access after reloading the page
			participants.forEach { NoteAccess.unlock(it.httpSession, id) }
		}
		if (ttl != null) {
			expiresAt = if (ttl.isZero) null else Instant.now() + ttl
		}
		dirty = true
		broadcast(mapOf("type" to "protection") + protection())
	}

	fun verifyPassword(password: String): Boolean {
		val hash = lock.withLock { passwordHash } ?: return true
		return Passwords.verify(password, hash)
	}

	/** Replaces the whole document as a regular update so that connected editors keep their local changes. */
	fun restore(participant: Participant, content: String, restoredSyntax: Syntax) = lock.withLock {
		checkOwner(participant)
		if (content != doc) {
			val changes = JsonNodeFactory.instance.arrayNode().add(
				JsonNodeFactory.instance.arrayNode().add(doc.length).apply { content.split('\n').forEach(::add) }
			)
			commit(content, listOf(Update(SERVER_CLIENT_ID, changes)))
		}
		applySettings(restoredSyntax, preview)
	}

	/** Disconnects everyone; the room must not be used afterwards. */
	fun close(reason: CloseStatus, message: Map<String, Any?>) = lock.withLock {
		closed = true
		broadcast(message)
		participants.forEach { runCatching { it.session.close(reason) } }
		participants.clear()
	}

	fun currentNote(): Note =
		lock.withLock { Note(id, readId, doc, syntax, preview, passwordHash, expiresAt, ownerKey, stopwatch) }

	fun isOwnedBy(clientKey: String?): Boolean = lock.withLock { clientKey != null && clientKey == ownerKey }

	/** Returns the current note if it changed since the last call. */
	fun takeSnapshotIfDirty(): Note? = lock.withLock {
		if (!dirty) return null
		dirty = false
		currentNote()
	}

	fun markDirty() = lock.withLock { dirty = true }

	fun isExpired(now: Instant): Boolean = lock.withLock { expiresAt?.let { it <= now } == true }

	fun isIdleFor(duration: Duration): Boolean = lock.withLock {
		val since = idleSince ?: return false
		return Duration.between(since, Instant.now()) >= duration
	}

	private fun commit(newDoc: String, updates: List<Update>) {
		require(newDoc.length <= maxNoteLength) { "note is too long" }
		doc = newDoc
		history.addAll(updates)
		while (history.size > MAX_HISTORY) history.removeFirst()
		version += updates.size
		dirty = true
		broadcast(mapOf("type" to "updates", "updates" to updates))
	}

	private fun applySettings(newSyntax: Syntax, newPreview: Boolean) {
		syntax = newSyntax
		// The split view renders Markdown, so it only makes sense for Markdown notes
		preview = newPreview && newSyntax == Syntax.MARKDOWN
		dirty = true
		broadcast(mapOf("type" to "settings") + settings())
	}

	private fun checkEditor(participant: Participant) {
		check(!participant.readOnly) { "read-only participant" }
	}

	private fun checkOwner(participant: Participant) {
		check(isOwner(participant)) { "only the creator of the note can do this" }
	}

	private fun isOwner(participant: Participant) =
		!participant.readOnly && participant.clientKey != null && participant.clientKey == ownerKey

	private fun updatesSince(clientVersion: Int): List<Update>? {
		val first = version - history.size
		if (clientVersion !in first..version) return null
		return history.drop(clientVersion - first)
	}

	private fun settings() = mapOf("syntax" to syntax.code, "preview" to preview)

	private fun protection() = mapOf("hasPassword" to (passwordHash != null), "expiresAt" to expiresAt?.toString())

	/** Clients count from the elapsed time instead of the start instant, so their clocks need not match the server's. */
	private fun stopwatchMessage() = mapOf(
		"type" to "stopwatch",
		"running" to stopwatch.running,
		"elapsed" to stopwatch.elapsedAt(Instant.now()).toMillis(),
	)

	private fun broadcastPresence() =
		broadcast(mapOf("type" to "presence", "users" to participants.map { it.toJson(isOwner(it)) }))

	private fun broadcast(message: Map<String, Any?>) {
		val text = TextMessage(mapper.writeValueAsString(message))
		participants.toList().forEach { send(it, text) }
	}

	private fun send(participant: Participant, message: Map<String, Any?>) =
		send(participant, TextMessage(mapper.writeValueAsString(message)))

	private fun send(participant: Participant, message: TextMessage) {
		try {
			participant.session.sendMessage(message)
		} catch (e: Exception) {
			log.debug("Failed to send to {}, closing", participant.session.id, e)
			participants.remove(participant)
			runCatching { participant.session.close(CloseStatus.SESSION_NOT_RELIABLE) }
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(NoteRoom::class.java)

		/** How many recent updates are kept so that reconnecting clients can catch up without a reload. */
		const val MAX_HISTORY = 1000

		const val SERVER_CLIENT_ID = "server"
	}
}
