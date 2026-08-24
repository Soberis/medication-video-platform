package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;

@Configuration(proxyBeanMethods = false)
@Profile({ "local", "test" })
class LocalBearerSecurityConfiguration {

	@Bean
	LocalBearerAuthenticationFilter localBearerAuthenticationFilter(
			@Value("${platform.security.local-bearer-token:}") String bearerToken,
			@Value("${platform.security.local-admin-bearer-token:}") String adminBearerToken,
			@Value("${platform.security.local-project-id}") UUID projectId) {
		return new LocalBearerAuthenticationFilter(bearerToken, adminBearerToken, projectId);
	}

}

@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfiguration {

	@Bean
	PasswordEncoder platformPasswordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	@Order(2)
	SecurityFilterChain platformSecurityFilterChain(HttpSecurity http,
			SecurityContextRepository securityContextRepository,
			ObjectProvider<LocalBearerAuthenticationFilter> localBearerAuthenticationFilter) throws Exception {
		var csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfTokenRepository.setCookiePath("/");
		http.securityMatcher(request -> {
			var requestUri = request.getRequestURI();
			return !requestUri.startsWith("/api/v1/internal/media-jobs/")
					&& !requestUri.startsWith("/api/v1/internal/ai-jobs/");
		})
			.securityContext(
					context -> context.securityContextRepository(securityContextRepository).requireExplicitSave(false))
			.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
				.ignoringRequestMatchers("/api/v1/auth/login")
				.ignoringRequestMatchers(new RequestHeaderRequestMatcher("Authorization")))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
				.sessionFixation(fixation -> fixation.migrateSession()))
			.requestCache(cache -> cache.disable())
			.exceptionHandling(exception -> exception
				.authenticationEntryPoint((request, response, failure) -> writeAuthenticationRequired(response))
				.accessDeniedHandler((request, response, failure) -> {
					var authentication = SecurityContextHolder.getContext().getAuthentication();
					if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
						writeAuthenticationRequired(response);
						return;
					}
					response.setStatus(403);
					response.setHeader("Cache-Control", "no-store");
					response.setContentType("application/problem+json");
					response.getWriter()
						.write("{\"error_code\":\"FORBIDDEN\","
								+ "\"error_message\":\"The authenticated actor is not allowed to perform this operation.\","
								+ "\"retryable\":false,\"request_id\":\"" + java.util.UUID.randomUUID() + "\"}");
				}))
			.authorizeHttpRequests(authorize -> authorize.requestMatchers("/actuator/health/**", "/api/v1/auth/login")
				.permitAll()
				.requestMatchers("/api/v1/**")
				.authenticated()
				.anyRequest()
				.denyAll());
		var localFilter = localBearerAuthenticationFilter.getIfAvailable();
		if (localFilter != null) {
			http.addFilterBefore(localFilter, UsernamePasswordAuthenticationFilter.class);
		}
		return http.build();
	}

	private static void writeAuthenticationRequired(jakarta.servlet.http.HttpServletResponse response)
			throws java.io.IOException {
		response.setStatus(401);
		response.setHeader("WWW-Authenticate", "Bearer");
		response.setHeader("Cache-Control", "no-store");
		response.setContentType("application/problem+json");
		response.getWriter()
			.write("{\"error_code\":\"UNAUTHENTICATED\","
					+ "\"error_message\":\"An authenticated platform session or service credential is required.\","
					+ "\"retryable\":false,\"request_id\":\"" + java.util.UUID.randomUUID() + "\"}");
	}

}
