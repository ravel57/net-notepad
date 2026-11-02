package ru.ravel.netnotepad.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.ravel.netnotepad.limits.ApiRateLimitInterceptor

@Configuration
class WebMvcConfig(
	private val apiRateLimitInterceptor: ApiRateLimitInterceptor,
) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(apiRateLimitInterceptor).addPathPatterns("/api/**")
	}
}
