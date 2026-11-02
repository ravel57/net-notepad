package ru.ravel.netnotepad.collab

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import ru.ravel.netnotepad.config.NetNotepadProperties
import ru.ravel.netnotepad.note.Note
import ru.ravel.netnotepad.note.NoteRepository
import ru.ravel.netnotepad.note.Passwords
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Keeps rooms of notes that are being edited, persists their changes and unloads unused ones. */
@Component
class NoteRooms(
	private val repository: NoteRepository,
	private val properties: NetNotepadProperties,
	private val mapper: JsonMapper,
) {
	private val rooms = ConcurrentHashMap<String, NoteRoom>()

	/** Read-only id to note id for loaded rooms, including notes that are not saved yet. */
	private val readIds = ConcurrentHashMap<String, String>()

	fun join(noteId: String, participant: Participant, clientEpoch: String?, clientVersion: Int?): Pair<NoteRoom, NoteRoom.JoinResult> {
		lateinit var result: Pair<NoteRoom, NoteRoom.JoinResult>
		rooms.compute(noteId) { id, existing ->
			val room = existing ?: load(id)
			result = room to room.join(participant, clientEpoch, clientVersion)
			room
		}
		return result
	}

	fun resolveReadId(readId: String): String? = readIds[readId] ?: repository.findIdByReadId(readId)

	fun isPasswordProtected(noteId: String): Boolean =
		rooms[noteId]?.hasPassword ?: (repository.find(noteId)?.passwordHash != null)

	fun verifyPassword(noteId: String, password: String): Boolean {
		rooms[noteId]?.let { return it.verifyPassword(password) }
		val hash = repository.find(noteId)?.passwordHash ?: return true
		return Passwords.verify(password, hash)
	}

	fun restore(room: NoteRoom, participant: Participant, versionId: Long) {
		val version = requireNotNull(repository.findVersion(room.id, versionId)) { "version not found" }
		// Keep the state being replaced so that the restore itself can be undone
		val current = room.currentNote()
		repository.save(current)
		repository.addVersion(current, properties.versions.interval, force = true, keep = properties.versions.maxPerNote)
		room.restore(participant, version.content, version.syntax)
	}

	@Scheduled(fixedDelayString = "\${netnotepad.flush-interval:2s}")
	fun flush() {
		val now = Instant.now()
		for (room in rooms.values) {
			if (room.isExpired(now)) expire(room) else save(room)
		}
		for (id in rooms.keys) {
			rooms.computeIfPresent(id) { _, room ->
				if (room.isIdleFor(properties.idleEviction)) {
					save(room)
					readIds.remove(room.readId)
					null
				} else {
					room
				}
			}
		}
	}

	@Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
	fun deleteStaleNotes() {
		val deleted = repository.deleteStale(properties.retention, rooms.keys.toList())
		if (deleted > 0) log.info("Deleted {} expired or stale notes", deleted)
	}

	@PreDestroy
	fun flushAll() = rooms.values.forEach(::save)

	private fun expire(room: NoteRoom) {
		rooms.remove(room.id)
		readIds.remove(room.readId)
		room.close(CloseStatus(CLOSE_EXPIRED, "expired"), mapOf("type" to "expired"))
		repository.delete(room.id)
	}

	private fun load(id: String): NoteRoom {
		var note = repository.find(id)
		if (note?.expiresAt?.isBefore(Instant.now()) == true) {
			repository.delete(id)
			note = null
		}
		note = note ?: Note.empty(id)
		readIds[note.readId] = id
		return NoteRoom(note, properties.maxNoteLength, properties.limits.participantsPerNote, mapper)
	}

	private fun save(room: NoteRoom) {
		val note = room.takeSnapshotIfDirty() ?: return
		try {
			repository.save(note)
			repository.addVersion(note, properties.versions.interval, keep = properties.versions.maxPerNote)
		} catch (e: Exception) {
			log.error("Failed to save note {}", note.id, e)
			room.markDirty()
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(NoteRooms::class.java)
		const val CLOSE_EXPIRED = 4410
	}
}
