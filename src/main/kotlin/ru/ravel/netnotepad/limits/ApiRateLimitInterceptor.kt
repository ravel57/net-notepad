package ru.ravel.netnotepad.limits

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class ApiRateLimitInterceptor(
	private val rateLimits: RateLimits,
) : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		if (rateLimits.api.tryConsume(request.remoteAddr)) return true
		response.status = HttpStatus.TOO_MANY_REQUESTS.value()
		return false
	}
}
