package ru.ravel.netnotepad.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("netnotepad")
data class NetNotepadProperties(
	val maxNoteLength: Int = 1_000_000,
	val idleEviction: Duration = Duration.ofSeconds(60),
	/** Notes not edited for this long are deleted; zero keeps them forever. */
	val retention: Duration = Duration.ofDays(365),
	val versions: Versions = Versions(),
	val limits: Limits = Limits(),
) {
	data class Versions(
		/** Minimum time between two automatic snapshots of a note. */
		val interval: Duration = Duration.ofMinutes(5),
		val maxPerNote: Int = 50,
	)

	data class Limits(
		val participantsPerNote: Int = 50,
		val connectionsPerIp: Int = 30,
		/** Sustained rate and burst of WebSocket messages from one participant. */
		val messagesPerSecond: Double = 50.0,
		val messageBurst: Int = 300,
		val unlockAttemptsPerMinute: Int = 10,
		val apiRequestsPerMinute: Int = 120,
	)
}
