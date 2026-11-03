package ru.ravel.netnotepad.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean
import ru.ravel.netnotepad.collab.ClientHandshakeInterceptor
import ru.ravel.netnotepad.collab.NoteWebSocketHandler

@Configuration
@EnableWebSocket
class WebSocketConfig(
	private val noteWebSocketHandler: NoteWebSocketHandler,
	private val clientHandshakeInterceptor: ClientHandshakeInterceptor,
) : WebSocketConfigurer {

	override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
		registry.addHandler(noteWebSocketHandler, "/ws/notes/*", "/ws/view/*")
			.addInterceptors(clientHandshakeInterceptor)
	}

	/** Large pastes and initial documents do not fit into the default 8 KB message buffer. */
	@Bean
	fun createWebSocketContainer() = ServletServerContainerFactoryBean().apply {
		setMaxTextMessageBufferSize(MAX_MESSAGE_SIZE)
	}

	companion object {
		const val MAX_MESSAGE_SIZE = 8 * 1024 * 1024
	}
}
