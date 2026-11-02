package ru.ravel.netnotepad.collab

import tools.jackson.databind.JsonNode

/**
 * Applies a CodeMirror 6 `ChangeSet` in its JSON form (`ChangeSet.toJSON()`) to a plain string.
 *
 * The JSON is an array of sections: a number keeps that many characters unchanged,
 * an array `[deleted, ...insertedLines]` replaces `deleted` characters with the lines joined by `\n`.
 */
object ChangeSetJson {

	fun apply(doc: String, changes: JsonNode): String {
		require(changes.isArray) { "changes must be an array" }
		val out = StringBuilder(doc.length)
		var pos = 0
		for (section in changes) {
			when {
				section.isIntegralNumber -> {
					val length = section.intValue()
					require(length >= 0 && pos + length <= doc.length) { "retain out of bounds" }
					out.append(doc, pos, pos + length)
					pos += length
				}

				section.isArray && !section.isEmpty -> {
					val first = section[0]
					require(first.isIntegralNumber) { "deleted length must be a number" }
					val deleted = first.intValue()
					require(deleted >= 0 && pos + deleted <= doc.length) { "delete out of bounds" }
					pos += deleted
					for (i in 1 until section.size()) {
						val line = section[i]
						require(line.isString) { "inserted line must be a string" }
						if (i > 1) out.append('\n')
						out.append(line.stringValue())
					}
				}

				else -> throw IllegalArgumentException("unexpected change section: $section")
			}
		}
		require(pos == doc.length) { "change set length ${pos} does not match document length ${doc.length}" }
		return out.toString()
	}
}
