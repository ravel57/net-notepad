package ru.ravel.netnotepad.note

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

@Repository
class NoteRepository(
	private val jdbc: JdbcClient,
) {

	fun find(id: String): Note? = jdbc.sql(
		"select id, read_id, content, syntax, preview, password_hash, expires_at, owner_key from notes where id = :id"
	)
		.param("id", id)
		.query { rs, _ ->
			Note(
				id = rs.getString("id"),
				readId = rs.getString("read_id"),
				content = rs.getString("content"),
				syntax = Syntax.fromCode(rs.getString("syntax")) ?: Syntax.PLAIN,
				preview = rs.getBoolean("preview"),
				passwordHash = rs.getString("password_hash"),
				expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
				ownerKey = rs.getString("owner_key"),
			)
		}
		.optional()
		.orElse(null)

	fun findIdByReadId(readId: String): String? = jdbc.sql("select id from notes where read_id = :readId")
		.param("readId", readId)
		.query(String::class.java)
		.optional()
		.orElse(null)

	fun save(note: Note) {
		jdbc.sql(
			"""
			insert into notes (id, read_id, content, syntax, preview, password_hash, expires_at, owner_key)
			values (:id, :readId, :content, :syntax, :preview, :passwordHash, :expiresAt, :ownerKey)
			on conflict (id) do update
			set content = excluded.content,
			    syntax = excluded.syntax,
			    preview = excluded.preview,
			    password_hash = excluded.password_hash,
			    expires_at = excluded.expires_at,
			    owner_key = excluded.owner_key,
			    updated_at = now()
			""".trimIndent()
		)
			.param("id", note.id)
			.param("readId", note.readId)
			.param("content", note.content)
			.param("syntax", note.syntax.code)
			.param("preview", note.preview)
			.param("passwordHash", note.passwordHash)
			.param("expiresAt", note.expiresAt?.let(Timestamp::from))
			.param("ownerKey", note.ownerKey)
			.update()
	}

	fun delete(id: String) {
		jdbc.sql("delete from notes where id = :id").param("id", id).update()
	}

	/** Deletes expired notes and notes untouched for [retention], except the ones in [keep]. */
	fun deleteStale(retention: Duration, keep: Collection<String>): Int {
		val conditions = buildList {
			add("expires_at < now()")
			if (!retention.isZero) add("updated_at < :staleBefore")
		}
		val sql = "delete from notes where (${conditions.joinToString(" or ")})" +
				if (keep.isEmpty()) "" else " and id not in (:keep)"
		var statement = jdbc.sql(sql)
		if (!retention.isZero) statement = statement.param("staleBefore", Timestamp.from(Instant.now() - retention))
		if (keep.isNotEmpty()) statement = statement.param("keep", keep)
		return statement.update()
	}

	/**
	 * Stores the note content as a version unless the latest version is younger than [minInterval]
	 * or has the same content. With [force] only identical content is skipped.
	 */
	fun addVersion(note: Note, minInterval: Duration, force: Boolean = false, keep: Int): Boolean {
		if (note.content.isEmpty()) return false
		val inserted = jdbc.sql(
			"""
			insert into note_versions (note_id, content, syntax)
			select :id, :content, :syntax
			where coalesce((select content from note_versions where note_id = :id order by id desc limit 1), '') <> :content
			  and (:force or not exists (select 1 from note_versions where note_id = :id and created_at > :recent))
			""".trimIndent()
		)
			.param("id", note.id)
			.param("content", note.content)
			.param("syntax", note.syntax.code)
			.param("force", force)
			.param("recent", Timestamp.from(Instant.now() - minInterval))
			.update() > 0
		if (inserted) {
			jdbc.sql(
				"""
				delete from note_versions
				where note_id = :id
				  and id not in (select id from note_versions where note_id = :id order by id desc limit :keep)
				""".trimIndent()
			)
				.param("id", note.id)
				.param("keep", keep)
				.update()
		}
		return inserted
	}

	fun findVersions(noteId: String): List<NoteVersion> = jdbc.sql(
		"select id, content, syntax, created_at from note_versions where note_id = :id order by id desc"
	)
		.param("id", noteId)
		.query(::mapVersion)
		.list()

	fun findVersion(noteId: String, versionId: Long): NoteVersion? = jdbc.sql(
		"select id, content, syntax, created_at from note_versions where note_id = :id and id = :versionId"
	)
		.param("id", noteId)
		.param("versionId", versionId)
		.query(::mapVersion)
		.optional()
		.orElse(null)

	private fun mapVersion(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = NoteVersion(
		id = rs.getLong("id"),
		content = rs.getString("content"),
		syntax = Syntax.fromCode(rs.getString("syntax")) ?: Syntax.PLAIN,
		createdAt = rs.getTimestamp("created_at").toInstant(),
	)
}
