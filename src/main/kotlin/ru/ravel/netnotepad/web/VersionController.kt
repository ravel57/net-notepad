package ru.ravel.netnotepad.web

import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.ravel.netnotepad.collab.NoteRooms
import ru.ravel.netnotepad.note.NoteAccess
import ru.ravel.netnotepad.note.NoteRepository
import java.time.Instant

/** Version history; available to editors only, since the path contains the editable id. */
@RestController
@RequestMapping("/api/notes/{id}/versions")
class VersionController(
	private val repository: NoteRepository,
	private val rooms: NoteRooms,
) {

	data class VersionSummary(val id: Long, val createdAt: Instant, val length: Int, val syntax: String)

	data class VersionContent(val id: Long, val createdAt: Instant, val content: String, val syntax: String)

	@GetMapping
	fun list(@PathVariable id: String, session: HttpSession): List<VersionSummary> {
		checkAccess(id, session)
		return repository.findVersions(id).map { VersionSummary(it.id, it.createdAt, it.content.length, it.syntax.code) }
	}

	@GetMapping("/{versionId}")
	fun get(@PathVariable id: String, @PathVariable versionId: Long, session: HttpSession): VersionContent {
		checkAccess(id, session)
		val version = repository.findVersion(id, versionId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
		return VersionContent(version.id, version.createdAt, version.content, version.syntax.code)
	}

	private fun checkAccess(id: String, session: HttpSession) {
		if (rooms.isPasswordProtected(id) && !NoteAccess.isUnlocked(session, id)) {
			throw ResponseStatusException(HttpStatus.FORBIDDEN)
		}
	}
}
