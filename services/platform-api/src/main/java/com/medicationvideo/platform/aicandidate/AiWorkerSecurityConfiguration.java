package com.medicationvideo.platform.aicandidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.medicationvideo.platform.mediaprivacy.LocalMediaWorkerAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@Profile({ "local", "test" })
class AiWorkerSecurityConfiguration {

	@Bean
	@Order(0)
	SecurityFilterChain aiWorkerSecurityFilterChain(HttpSecurity http,
			@Value("${platform.security.local-ai-worker-bearer-token:}") String bearerToken,
			@Value("${platform.security.local-ai-worker-bearer-token-file:}") String bearerTokenFile) throws Exception {
		var workerFilter = new LocalMediaWorkerAuthenticationFilter(resolveBearerToken(bearerToken, bearerTokenFile),
				"local-ai-worker", "ROLE_AI_WORKER");
		return http.securityMatcher("/api/v1/internal/ai-jobs/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
			.addFilterBefore(workerFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	static String resolveBearerToken(String bearerToken, String bearerTokenFile) {
		var direct = bearerToken == null ? "" : bearerToken.trim();
		var file = bearerTokenFile == null ? "" : bearerTokenFile.trim();
		if (!direct.isEmpty() && !file.isEmpty()) {
			throw new IllegalStateException(
					"Configure exactly one of AI_WORKER_BEARER_TOKEN or AI_WORKER_BEARER_TOKEN_FILE.");
		}
		if (file.isEmpty()) {
			return direct;
		}
		var path = Path.of(file);
		if (!path.isAbsolute()) {
			throw new IllegalStateException("AI_WORKER_BEARER_TOKEN_FILE must be absolute.");
		}
		try {
			return Files.readString(path).trim();
		}
		catch (IOException exception) {
			throw new IllegalStateException("AI_WORKER_BEARER_TOKEN_FILE could not be read.", exception);
		}
	}

}
