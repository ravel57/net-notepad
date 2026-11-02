package ru.ravel.netnotepad

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import ru.ravel.netnotepad.collab.NoteRooms
import ru.ravel.netnotepad.note.NoteIds
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Needs the PostgreSQL database configured in application.yaml
@SpringBootTest(
	webEnvironment = WebEnvironment.RANDOM_PORT,
	properties = [
		"netnotepad.limits.message-burst=40",
		"netnotepad.limits.messages-per-second=1",
	],
)
class NetNotepadApplicationTests {

	@LocalServerPort
	private var port = 0

	@Autowired
	private lateinit var mapper: JsonMapper

	@Autowired
	private lateinit var rooms: NoteRooms

	private val http = HttpClient.newHttpClient()

	private inner class Client(path: String, cookie: String? = null) {
		private val messages = LinkedBlockingQueue<JsonNode>()
		val closed = CompletableFuture<CloseStatus>()
		val session: WebSocketSession = StandardWebSocketClient().execute(
			object : TextWebSocketHandler() {
				override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
					messages.add(mapper.readTree(message.payload))
				}

				override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
					closed.complete(status)
				}
			},
			WebSocketHttpHeaders().apply { cookie?.let { add("Cookie", it) } },
			URI("ws://localhost:$port/ws/$path"),
		).get(5, TimeUnit.SECONDS)

		/** Skips messages of other types until one of [type] arrives. */
		fun await(type: String, condition: (JsonNode) -> Boolean = { true }): JsonNode {
			while (true) {
				val message = assertNotNull(messages.poll(5, TimeUnit.SECONDS), "no '$type' message")
				if (message["type"].asString() == type && condition(message)) return message
			}
		}

		fun closeCode(): Int = closed.get(5, TimeUnit.SECONDS).code

		fun send(json: String) = session.sendMessage(TextMessage(json))
	}

	private fun editor(noteId: String, cookie: String? = null) = Client("notes/$noteId", cookie)

	@Test
	fun `participants share edits, settings and cursors`() {
		val noteId = NoteIds.generate()
		val a = editor(noteId)
		val init = a.await("init")
		assertEquals("", init["doc"].asString())
		assertEquals(0, init["version"].asInt())

		val b = editor(noteId)
		val bId = b.await("init")["you"].asString()
		a.await("presence") { it["users"].size() == 2 }

		b.send("""{"type":"profile","name":"Боб","color":"#00ff00"}""")
		a.await("presence") { presence -> presence["users"].any { it["name"].asString() == "Боб" } }

		a.send("""{"type":"push","version":0,"updates":[{"clientID":"a","changes":[[0,"hello"]]}]}""")
		assertEquals("a", a.await("updates")["updates"][0]["clientID"].asString())
		assertEquals("a", b.await("updates")["updates"][0]["clientID"].asString())

		// B did not account for A's update yet
		b.send("""{"type":"push","version":0,"updates":[{"clientID":"b","changes":[[0,"!"]]}]}""")
		b.await("conflict")
		b.send("""{"type":"push","version":1,"updates":[{"clientID":"b","changes":[5,[0," world"]]}]}""")
		a.await("updates")

		b.send("""{"type":"cursor","version":2,"ranges":[[0,5]]}""")
		val cursor = a.await("cursor")
		assertEquals(bId, cursor["id"].asString())
		assertEquals(5, cursor["ranges"][0][1].asInt())

		b.send("""{"type":"settings","syntax":"markdown","preview":true}""")
		assertEquals(true, a.await("settings")["preview"].asBoolean())
		a.send("""{"type":"settings","syntax":"java","preview":true}""")
		val javaSettings = b.await("settings") { it["syntax"].asString() == "java" }
		assertEquals(false, javaSettings["preview"].asBoolean())

		// A reconnecting client only receives the updates it missed
		val resumed = Client("notes/$noteId?epoch=${init["epoch"].asString()}&version=1")
		val resume = resumed.await("resume")
		assertEquals(1, resume["updates"].size())
		assertEquals("b", resume["updates"][0]["clientID"].asString())

		listOf(a, b, resumed).forEach { it.session.close() }
	}

	@Test
	fun `read-only link shows the note but rejects edits`() {
		val noteId = NoteIds.generate()
		val editor = editor(noteId)
		val readId = editor.await("init")["readId"].asString()
		editor.send("""{"type":"push","version":0,"updates":[{"clientID":"a","changes":[[0,"secret"]]}]}""")
		editor.await("updates")

		val viewer = Client("view/$readId")
		val init = viewer.await("init")
		assertEquals("secret", init["doc"].asString())
		assertEquals(true, init["readOnly"].asBoolean())
		assertTrue(init["readId"].isNull, "viewer must not learn more than the read-only id")

		viewer.send("""{"type":"push","version":1,"updates":[{"clientID":"v","changes":[6,[0,"!"]]}]}""")
		viewer.await("error")
		editor.session.close()
	}

	@Test
	fun `password protects the note`() {
		val noteId = NoteIds.generate()
		val editor = editor(noteId)
		editor.await("init")
		editor.send("""{"type":"protect","password":"s3cret","ttl":3600}""")
		val protection = editor.await("protection")
		assertEquals(true, protection["hasPassword"].asBoolean())
		assertNotNull(protection["expiresAt"].asString())

		assertEquals(4401, editor(noteId).closeCode())
		assertEquals(200, unlock(noteId, "wrong").statusCode())

		val unlocked = unlock(noteId, "s3cret")
		assertEquals(302, unlocked.statusCode())
		val cookie = unlocked.headers().firstValue("Set-Cookie").get().substringBefore(';')
		editor(noteId, cookie).await("init")
		editor.session.close()
	}

	@Test
	fun `versions can be listed and restored`() {
		val noteId = NoteIds.generate()
		val editor = editor(noteId)
		editor.await("init")
		editor.send("""{"type":"push","version":0,"updates":[{"clientID":"a","changes":[[0,"first"]]}]}""")
		editor.await("updates")
		rooms.flush()
		editor.send("""{"type":"push","version":1,"updates":[{"clientID":"a","changes":[[5,"second"]]}]}""")
		editor.await("updates")

		val versions = mapper.readTree(get("/api/notes/$noteId/versions").body())
		assertEquals(1, versions.size())
		editor.send("""{"type":"restore","versionId":${versions[0]["id"].asLong()}}""")
		val restore = editor.await("updates")["updates"][0]
		assertEquals("server", restore["clientID"].asString())
		assertEquals("first", restore["changes"][0][1].asString())

		// The replaced state is kept as a version too
		assertEquals(2, mapper.readTree(get("/api/notes/$noteId/versions").body()).size())
		editor.session.close()
	}

	@Test
	fun `invalid change set is rejected`() {
		val client = editor(NoteIds.generate())
		client.await("init")
		client.send("""{"type":"push","version":0,"updates":[{"clientID":"a","changes":[5]}]}""")
		client.await("error")
	}

	@Test
	fun `message flood closes the connection`() {
		val client = editor(NoteIds.generate())
		client.await("init")
		repeat(60) { if (client.session.isOpen) client.send("""{"type":"ping"}""") }
		assertEquals(4429, client.closeCode())
	}

	@Test
	fun `unknown read-only link is not found`() {
		assertEquals(404, get("/r/${NoteIds.generate()}").statusCode())
		assertNull(rooms.resolveReadId(NoteIds.generate()))
	}

	private fun get(path: String): HttpResponse<String> =
		http.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(), HttpResponse.BodyHandlers.ofString())

	private fun unlock(noteId: String, password: String): HttpResponse<String> = http.send(
		HttpRequest.newBuilder(URI("http://localhost:$port/$noteId/unlock"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString("password=" + URLEncoder.encode(password, Charsets.UTF_8)))
			.build(),
		HttpResponse.BodyHandlers.ofString(),
	)
}
