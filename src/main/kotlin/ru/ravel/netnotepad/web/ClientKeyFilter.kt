package ru.ravel.netnotepad.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

/**
 * Gives every browser a long-lived random key in a cookie. A note remembers the key of the browser
 * that created it: that browser is the owner. Only the SHA-256 hash of the key is stored and compared,
 * it is exposed to the application as the [ATTRIBUTE] request attribute.
 */
@Component
class ClientKeyFilter : OncePerRequestFilter() {

	override fun shouldNotFilter(request: HttpServletRequest): Boolean {
		val path = request.requestURI
		return path.startsWith("/js/") || path.startsWith("/css/") || path.startsWith("/actuator")
	}

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
		var key = request.cookies?.firstOrNull { it.name == COOKIE }?.value?.takeIf { KEY_PATTERN.matches(it) }
		if (key == null) {
			key = generate()
			val cookie = ResponseCookie.from(COOKIE, key)
				.path("/")
				.httpOnly(true)
				.secure(request.isSecure)
				.sameSite("Lax")
				.maxAge(MAX_AGE)
				.build()
			response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
		}
		request.setAttribute(ATTRIBUTE, hash(key))
		chain.doFilter(request, response)
	}

	companion object {
		const val ATTRIBUTE = "netnotepad.clientKey"
		private const val COOKIE = "nn_client"

		/** Browsers cap cookie lifetime at about 400 days. */
		private val MAX_AGE = Duration.ofDays(400)
		private val KEY_PATTERN = Regex("[A-Za-z0-9_-]{43}")
		private val random = SecureRandom()

		fun clientKey(request: HttpServletRequest): String? = request.getAttribute(ATTRIBUTE) as? String

		private fun generate(): String =
			Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

		private fun hash(key: String): String =
			HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
	}
}
