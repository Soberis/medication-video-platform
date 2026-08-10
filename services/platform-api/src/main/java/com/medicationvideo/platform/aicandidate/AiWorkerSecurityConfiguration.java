package com.medicationvideo.platform.aicandidate;

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
			@Value("${platform.security.local-ai-worker-bearer-token:}") String bearerToken) throws Exception {
		var workerFilter = new LocalMediaWorkerAuthenticationFilter(bearerToken, "local-ai-worker", "ROLE_AI_WORKER");
		return http.securityMatcher("/api/v1/internal/ai-jobs/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
			.addFilterBefore(workerFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

}
