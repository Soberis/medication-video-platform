package com.medicationvideo.platform.assetcapture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@Profile({ "local", "test" })
class CaptureSyncWorkerSecurityConfiguration {

	@Bean
	@Order(1)
	SecurityFilterChain captureSyncWorkerSecurityFilterChain(HttpSecurity http,
			@Value("${platform.security.local-worker-bearer-token:}") String bearerToken) throws Exception {
		var workerFilter = new CaptureSyncWorkerAuthenticationFilter(bearerToken);
		return http.securityMatcher("/api/v1/internal/capture-sync-jobs/**")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
			.addFilterBefore(workerFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

}
