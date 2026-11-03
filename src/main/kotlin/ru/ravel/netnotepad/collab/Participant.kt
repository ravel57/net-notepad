package ru.ravel.netnotepad.collab

import jakarta.servlet.http.HttpSession
import org.springframework.web.socket.WebSocketSession
import ru.ravel.netnotepad.limits.TokenBucket

/** A connected browser tab. */
class Participant(
	/** Thread-safe session (see `ConcurrentWebSocketSessionDecorator`). */
	val session: WebSocketSession,
	val id: String,
	val readOnly: Boolean,
	val httpSession: HttpSession?,
	/** Hashed browser key, see `ClientKeyFilter`. */
	val clientKey: String?,
	val messageLimit: TokenBucket,
) {
	@Volatile
	var name: String = "Гость"

	@Volatile
	var color: String = "#7c8495"

	fun toJson(owner: Boolean) = mapOf("id" to id, "name" to name, "color" to color, "readOnly" to readOnly, "owner" to owner)
}
