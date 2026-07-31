package com.medicationvideo.platform.datasetexport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
@Profile({ "local", "test" })
class ExportWorkerSecurityConfiguration {

	@Bean
	@Order(1)
	SecurityFilterChain exportWorkerSecurityFilterChain(HttpSecurity http,
			@Value("${platform.security.local-export-worker-bearer-token:}") String bearerToken,
			@Value("${platform.security.local-worker-bearer-token:}") String mediaWorkerToken) throws Exception {
		if (!bearerToken.isEmpty() && bearerToken.equals(mediaWorkerToken)) {
			throw new IllegalArgumentException("Media and Export Worker bearer tokens must be different.");
		}
		var workerFilter = new LocalExportWorkerAuthenticationFilter(bearerToken);
		return http.securityMatcher("/api/v1/internal/dataset-exports/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
			.addFilterBefore(workerFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

}

final class LocalExportWorkerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final byte[] expectedToken;

	LocalExportWorkerAuthenticationFilter(String expectedToken) {
		this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		var authorization = request.getHeader("Authorization");
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX) || this.expectedToken.length < 32
				|| !MessageDigest.isEqual(
						authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8),
						this.expectedToken)) {
			unauthorized(response);
			return;
		}

		var authentication = new UsernamePasswordAuthenticationToken(new ExportWorkerActor("local-export-worker"), null,
				List.of(new SimpleGrantedAuthority("ROLE_EXPORT_WORKER")));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		filterChain.doFilter(request, response);
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader("WWW-Authenticate", "Bearer");
		response.setHeader("Cache-Control", "no-store");
		response.setContentType("application/problem+json");
		response.getWriter()
			.write("{\"error_code\":\"UNAUTHORIZED\","
					+ "\"error_message\":\"A valid Export Worker bearer token is required.\"," + "\"retryable\":false,"
					+ "\"request_id\":\"" + UUID.randomUUID() + "\"}");
	}

}
