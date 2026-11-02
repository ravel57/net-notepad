package ru.ravel.netnotepad.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import ru.ravel.netnotepad.collab.NoteRooms
import ru.ravel.netnotepad.limits.RateLimits
import ru.ravel.netnotepad.note.NoteAccess
import ru.ravel.netnotepad.note.NoteIds

@Controller
class NoteController(
	private val rooms: NoteRooms,
	private val rateLimits: RateLimits,
) {

	/** A note is created lazily: it is stored only after the first edit. */
	@GetMapping("/", "/new")
	fun newNote() = "redirect:/${NoteIds.generate()}"

	@GetMapping("/{id:$ID}")
	fun edit(@PathVariable id: String, session: HttpSession, model: Model): String =
		page(id, id, readOnly = false, session, model)

	@GetMapping("/r/{readId:$ID}")
	fun view(@PathVariable readId: String, session: HttpSession, model: Model, response: HttpServletResponse): String {
		val noteId = rooms.resolveReadId(readId) ?: return notFound(response)
		return page(noteId, readId, readOnly = true, session, model)
	}

	@PostMapping("/{id:$ID}/unlock")
	fun unlockEdit(
		@PathVariable id: String,
		@RequestParam password: String,
		request: HttpServletRequest,
		session: HttpSession,
		model: Model,
	): String = unlock(id, id, "/$id", password, request, session, model)

	@PostMapping("/r/{readId:$ID}/unlock")
	fun unlockView(
		@PathVariable readId: String,
		@RequestParam password: String,
		request: HttpServletRequest,
		response: HttpServletResponse,
		session: HttpSession,
		model: Model,
	): String {
		val noteId = rooms.resolveReadId(readId) ?: return notFound(response)
		return unlock(noteId, readId, "/r/$readId", password, request, session, model)
	}

	private fun page(noteId: String, key: String, readOnly: Boolean, session: HttpSession, model: Model): String {
		model.addAttribute("key", key)
		model.addAttribute("readOnly", readOnly)
		if (rooms.isPasswordProtected(noteId) && !NoteAccess.isUnlocked(session, noteId)) {
			model.addAttribute("action", if (readOnly) "/r/$key/unlock" else "/$key/unlock")
			return "unlock"
		}
		return "note"
	}

	private fun unlock(
		noteId: String,
		key: String,
		target: String,
		password: String,
		request: HttpServletRequest,
		session: HttpSession,
		model: Model,
	): String {
		val error = when {
			!rateLimits.unlock.tryConsume(request.remoteAddr) -> "Слишком много попыток, подождите минуту"
			!rooms.verifyPassword(noteId, password) -> "Неверный пароль"
			else -> null
		}
		if (error == null) {
			NoteAccess.unlock(session, noteId)
			return "redirect:$target"
		}
		model.addAttribute("key", key)
		model.addAttribute("action", "$target/unlock")
		model.addAttribute("error", error)
		return "unlock"
	}

	private fun notFound(response: HttpServletResponse): String {
		response.status = HttpStatus.NOT_FOUND.value()
		return "not-found"
	}

	companion object {
		private const val ID = "[A-Za-z0-9_-]{4,32}"
	}
}
