package ru.ravel.netnotepad.collab

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import ru.ravel.netnotepad.web.ClientKeyFilter

/**
 * Exposes to the WebSocket handler the HTTP session, which holds the notes unlocked with a password,
 * and the browser key, which tells whether the participant owns the note.
 */
@Component
class ClientHandshakeInterceptor : HandshakeInterceptor {

	override fun beforeHandshake(
		request: ServerHttpRequest,
		response: ServerHttpResponse,
		wsHandler: WebSocketHandler,
		attributes: MutableMap<String, Any>,
	): Boolean {
		val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest ?: return true
		attributes[HTTP_SESSION] = servletRequest.getSession(true)
		ClientKeyFilter.clientKey(servletRequest)?.let { attributes[CLIENT_KEY] = it }
		return true
	}

	override fun afterHandshake(
		request: ServerHttpRequest,
		response: ServerHttpResponse,
		wsHandler: WebSocketHandler,
		exception: Exception?,
	) = Unit

	companion object {
		const val HTTP_SESSION = "httpSession"
		const val CLIENT_KEY = "clientKey"
	}
}
