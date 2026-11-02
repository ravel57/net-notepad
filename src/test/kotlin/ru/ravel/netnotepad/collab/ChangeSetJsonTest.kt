package ru.ravel.netnotepad.collab

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangeSetJsonTest {

	private val mapper = JsonMapper.builder().build()

	private fun apply(doc: String, changes: String) = ChangeSetJson.apply(doc, mapper.readTree(changes))

	@Test
	fun `inserts text`() {
		assertEquals("hello, world", apply("hello world", """[5, [0, ","], 6]"""))
	}

	@Test
	fun `inserts multiple lines`() {
		assertEquals("a\nb\nc", apply("ac", """[1, [0, "", "b", ""], 1]"""))
	}

	@Test
	fun `deletes and replaces text`() {
		assertEquals("hi", apply("hello", """[[5, "hi"]]"""))
		assertEquals("ho", apply("hello", """[1, [3], 1]"""))
	}

	@Test
	fun `applies to empty document`() {
		assertEquals("class A {}", apply("", """[[0, "class A {}"]]"""))
	}

	@Test
	fun `rejects change set of wrong length`() {
		assertFailsWith<IllegalArgumentException> { apply("hello", """[4]""") }
		assertFailsWith<IllegalArgumentException> { apply("hello", """[6]""") }
		assertFailsWith<IllegalArgumentException> { apply("hello", """[[6, "x"]]""") }
	}

	@Test
	fun `rejects malformed sections`() {
		assertFailsWith<IllegalArgumentException> { apply("hello", """{"a": 1}""") }
		assertFailsWith<IllegalArgumentException> { apply("hello", """[5, "x"]""") }
		assertFailsWith<IllegalArgumentException> { apply("hello", """[5, [0, 1]]""") }
	}

	@Test
	fun `missing message fields fall back to defaults`() {
		val json = mapper.readTree("""{}""")
		assertEquals("", json.path("type").asString(""))
		assertEquals(-1, json.path("version").asInt(-1))
		assertEquals(false, json.path("preview").asBoolean(false))
	}
}
