package ru.ravel.netnotepad.collab

import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.util.UriComponentsBuilder
import ru.ravel.netnotepad.config.WebSocketConfig
import ru.ravel.netnotepad.limits.ConnectionLimiter
import ru.ravel.netnotepad.limits.RateLimits
import ru.ravel.netnotepad.note.NoteIds
import ru.ravel.netnotepad.note.Passwords
import ru.ravel.netnotepad.note.Syntax
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * WebSocket endpoints `/ws/notes/{id}` (editors) and `/ws/view/{readId}` (read-only viewers).
 * Query parameters `epoch` and `version` are sent by reconnecting clients to receive only the updates they missed.
 */
@Component
class NoteWebSocketHandler(
	private val rooms: NoteRooms,
	private val rateLimits: RateLimits,
	private val connectionLimiter: ConnectionLimiter,
	private val mapper: JsonMapper,
) : TextWebSocketHandler() {

	override fun afterConnectionEstablished(session: WebSocketSession) {
		val uri = session.uri ?: return session.close(CloseStatus.BAD_DATA)
		val ip = session.remoteAddress?.address?.hostAddress ?: "unknown"
		if (!connectionLimiter.tryAcquire(ip)) {
			session.close(CloseStatus(CLOSE_LIMIT, "too many connections"))
			return
		}
		session.attributes[IP] = ip

		val readOnly = uri.path.startsWith("/ws/view/")
		val key = uri.path.substringAfterLast('/')
		val noteId = when {
			!NoteIds.isValid(key) -> null
			readOnly -> rooms.resolveReadId(key)
			else -> key
		} ?: return session.close(CloseStatus(CLOSE_NOT_FOUND, "note not found"))

		val params = UriComponentsBuilder.fromUri(uri).build().queryParams
		val participant = Participant(
			// Messages are broadcast from other participants' threads, so sending has to be serialized
			session = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, WebSocketConfig.MAX_MESSAGE_SIZE * 2),
			id = NoteIds.generate(),
			readOnly = readOnly,
			httpSession = session.attributes[HttpSessionInterceptor.HTTP_SESSION] as? HttpSession,
			messageLimit = rateLimits.newMessageBucket(),
		)
		val (room, result) = rooms.join(noteId, participant, params.getFirst("epoch"), params.getFirst("version")?.toIntOrNull())
		when (result) {
			NoteRoom.JoinResult.JOINED -> {
				session.attributes[ROOM] = room
				session.attributes[PARTICIPANT] = participant
			}

			NoteRoom.JoinResult.LOCKED -> session.close(CloseStatus(CLOSE_LOCKED, "password required"))
			NoteRoom.JoinResult.FULL -> session.close(CloseStatus(CLOSE_LIMIT, "too many participants"))
			NoteRoom.JoinResult.CLOSED -> session.close(CloseStatus(NoteRooms.CLOSE_EXPIRED, "expired"))
		}
	}

	override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
		val room = session.attributes[ROOM] as? NoteRoom ?: return
		val participant = session.attributes[PARTICIPANT] as Participant
		if (!participant.messageLimit.tryConsume()) {
			log.warn("Participant {} of note {} exceeded the message rate", participant.id, room.id)
			participant.session.close(CloseStatus(CLOSE_LIMIT, "too many messages"))
			return
		}
		try {
			val json = mapper.readTree(message.payload)
			when (json.path("type").asString("")) {
				"push" -> room.push(participant, json.path("version").asInt(-1), parseUpdates(json.path("updates")))
				"cursor" -> room.moveCursor(participant, json.path("version").asInt(-1), parseRanges(json.path("ranges")))
				"profile" -> room.changeProfile(participant, parseName(json.path("name")), parseColor(json.path("color")))
				"settings" -> room.changeSettings(
					participant,
					Syntax.fromCode(json.path("syntax").asString("")) ?: Syntax.PLAIN,
					json.path("preview").asBoolean(false),
				)

				"protect" -> room.protect(participant, parsePassword(json), parseTtl(json))
				"restore" -> rooms.restore(room, participant, json.path("versionId").asLong(-1))
				// Makes sure an untouched note is saved, so that its read-only link works
				"touch" -> room.markDirty()
				"ping" -> Unit
				else -> throw IllegalArgumentException("unknown message type")
			}
		} catch (e: Exception) {
			log.warn("Rejected message from {} in note {}: {}", participant.id, room.id, e.message)
			participant.session.sendMessage(TextMessage(mapper.writeValueAsString(mapOf("type" to "error", "message" to e.message))))
			participant.session.close(CloseStatus.BAD_DATA)
		}
	}

	override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
		(session.attributes[IP] as? String)?.let(connectionLimiter::release)
		val room = session.attributes[ROOM] as? NoteRoom ?: return
		room.leave(session.attributes[PARTICIPANT] as Participant)
	}

	private fun parseUpdates(json: JsonNode): List<NoteRoom.Update> {
		require(json.isArray && !json.isEmpty) { "updates must be a non-empty array" }
		return json.map { update ->
			val clientID = update.path("clientID").asString("")
			require(clientID.isNotEmpty() && clientID.length <= 64 && clientID != NoteRoom.SERVER_CLIENT_ID) { "invalid clientID" }
			NoteRoom.Update(clientID, update.path("changes"))
		}
	}

	private fun parseRanges(json: JsonNode): List<IntArray> {
		require(json.isArray && json.size() in 1..MAX_RANGES) { "invalid ranges" }
		return json.map { range ->
			require(range.isArray && range.size() == 2 && range.all { it.isIntegralNumber && it.intValue() >= 0 }) { "invalid range" }
			intArrayOf(range[0].intValue(), range[1].intValue())
		}
	}

	private fun parseName(json: JsonNode): String =
		json.asString("").trim().take(MAX_NAME_LENGTH).ifEmpty { "Гость" }

	private fun parseColor(json: JsonNode): String =
		json.asString("").takeIf { COLOR.matches(it) } ?: "#7c8495"

	/** `null` keeps the password, `""` removes it. */
	private fun parsePassword(json: JsonNode): String? {
		if (!json.has("password")) return null
		val password = json.path("password").asString("")
		require(password.length <= MAX_PASSWORD_LENGTH) { "password is too long" }
		return if (password.isEmpty()) "" else Passwords.hash(password)
	}

	/** `null` keeps the lifetime, zero removes it. */
	private fun parseTtl(json: JsonNode): Duration? {
		if (!json.has("ttl")) return null
		val seconds = json.path("ttl").asLong(0)
		require(seconds in 0..MAX_TTL.seconds) { "invalid ttl" }
		return Duration.ofSeconds(seconds)
	}

	companion object {
		private val log = LoggerFactory.getLogger(NoteWebSocketHandler::class.java)
		private const val IP = "ip"
		private const val ROOM = "room"
		private const val PARTICIPANT = "participant"
		private const val SEND_TIME_LIMIT_MS = 10_000
		private const val MAX_RANGES = 50
		private const val MAX_NAME_LENGTH = 40
		private const val MAX_PASSWORD_LENGTH = 200
		private val MAX_TTL = Duration.ofDays(365)
		private val COLOR = Regex("#[0-9a-fA-F]{6}")

		const val CLOSE_LOCKED = 4401
		const val CLOSE_NOT_FOUND = 4404
		const val CLOSE_LIMIT = 4429
	}
}
