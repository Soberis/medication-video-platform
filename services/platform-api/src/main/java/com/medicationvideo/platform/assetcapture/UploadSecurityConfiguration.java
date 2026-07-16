package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

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
@Profile({"local", "test"})
class UploadSecurityConfiguration {

	@Bean
	@Order(2)
	SecurityFilterChain uploadSecurityFilterChain(
			HttpSecurity http,
			@Value("${platform.security.local-bearer-token:}") String bearerToken,
			@Value("${platform.security.local-project-id}") UUID projectId) throws Exception {
		var localBearerFilter = new LocalBearerAuthenticationFilter(bearerToken, projectId);
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/actuator/health/**").permitAll()
						.requestMatchers("/api/v1/**").authenticated()
						.anyRequest().denyAll())
				.addFilterBefore(localBearerFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}

@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class DenyAllApiSecurityConfiguration {

	@Bean
	SecurityFilterChain denyAllApiSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/actuator/health/**").permitAll()
						.anyRequest().denyAll())
				.build();
	}
}
