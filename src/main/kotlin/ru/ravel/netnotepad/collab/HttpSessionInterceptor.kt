package ru.ravel.netnotepad.collab

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/** Exposes the HTTP session to the WebSocket handler: it holds the notes unlocked with a password. */
@Component
class HttpSessionInterceptor : HandshakeInterceptor {

	override fun beforeHandshake(
		request: ServerHttpRequest,
		response: ServerHttpResponse,
		wsHandler: WebSocketHandler,
		attributes: MutableMap<String, Any>,
	): Boolean {
		(request as? ServletServerHttpRequest)?.servletRequest?.getSession(true)?.let { attributes[HTTP_SESSION] = it }
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
	}
}
