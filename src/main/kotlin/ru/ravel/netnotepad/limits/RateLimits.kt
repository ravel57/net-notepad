package ru.ravel.netnotepad.limits

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.ravel.netnotepad.config.NetNotepadProperties
import java.util.concurrent.ConcurrentHashMap

/** Token bucket: allows bursts of [capacity] and [refillPerSecond] operations on average. */
class TokenBucket(private val capacity: Double, private val refillPerSecond: Double) {
	private var tokens = capacity
	private var updatedAt = System.nanoTime()

	@Synchronized
	fun tryConsume(): Boolean {
		refill()
		if (tokens < 1) return false
		tokens -= 1
		return true
	}

	@Synchronized
	fun isFull(): Boolean {
		refill()
		return tokens >= capacity
	}

	private fun refill() {
		val now = System.nanoTime()
		tokens = minOf(capacity, tokens + (now - updatedAt) / 1e9 * refillPerSecond)
		updatedAt = now
	}
}

class KeyedRateLimiter(private val capacity: Double, private val refillPerSecond: Double) {
	private val buckets = ConcurrentHashMap<String, TokenBucket>()

	fun tryConsume(key: String) = buckets.computeIfAbsent(key) { TokenBucket(capacity, refillPerSecond) }.tryConsume()

	fun cleanup() = buckets.values.removeIf { it.isFull() }
}

@Component
class RateLimits(properties: NetNotepadProperties) {
	private val limits = properties.limits

	/** Password guesses per IP address. */
	val unlock = KeyedRateLimiter(limits.unlockAttemptsPerMinute.toDouble(), limits.unlockAttemptsPerMinute / 60.0)

	/** REST API requests per IP address. */
	val api = KeyedRateLimiter(limits.apiRequestsPerMinute.toDouble(), limits.apiRequestsPerMinute / 60.0)

	fun newMessageBucket() = TokenBucket(limits.messageBurst.toDouble(), limits.messagesPerSecond)

	@Scheduled(fixedDelay = 300_000)
	fun cleanup() {
		unlock.cleanup()
		api.cleanup()
	}
}

/** Counts open WebSocket connections per IP address. */
@Component
class ConnectionLimiter(private val properties: NetNotepadProperties) {
	private val connections = ConcurrentHashMap<String, Int>()

	fun tryAcquire(ip: String): Boolean {
		var acquired = false
		connections.compute(ip) { _, count ->
			val current = count ?: 0
			if (current < properties.limits.connectionsPerIp) {
				acquired = true
				current + 1
			} else {
				current
			}
		}
		return acquired
	}

	fun release(ip: String) {
		connections.computeIfPresent(ip) { _, count -> if (count > 1) count - 1 else null }
	}
}
