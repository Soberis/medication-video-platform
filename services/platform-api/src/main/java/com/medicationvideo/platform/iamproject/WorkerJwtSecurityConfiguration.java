package com.medicationvideo.platform.iamproject;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Production machine-identity boundary for Media and Export Workers.
 *
 * <p>
 * Local and test profiles keep their isolated synthetic bearer filters. Every other
 * profile requires signed JWTs with an exact issuer, a Worker-specific audience, a
 * subject and a bounded validity window.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
class WorkerJwtSecurityConfiguration {

	private static final String MEDIA_WORKER_ROLE = "ROLE_MEDIA_WORKER";

	private static final String EXPORT_WORKER_ROLE = "ROLE_EXPORT_WORKER";

	private static final String AI_WORKER_ROLE = "ROLE_AI_WORKER";

	private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

	private static final OAuth2Error MISSING_SUBJECT = new OAuth2Error("invalid_token",
			"A machine identity subject is required.", null);

	private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error("invalid_token",
			"The Worker token audience is invalid.", null);

	@Bean("mediaWorkerJwtDecoder")
	JwtDecoder mediaWorkerJwtDecoder(@Value("${platform.security.worker-jwt.issuer-uri:}") String issuerUri,
			@Value("${platform.security.worker-jwt.jwk-set-uri:}") String jwkSetUri,
			@Value("${platform.security.worker-jwt.media-audience:}") String audience,
			@Value("${platform.security.worker-jwt.clock-skew:PT1M}") Duration clockSkew) {
		return decoder(issuerUri, jwkSetUri, audience, clockSkew);
	}

	@Bean("exportWorkerJwtDecoder")
	JwtDecoder exportWorkerJwtDecoder(@Value("${platform.security.worker-jwt.issuer-uri:}") String issuerUri,
			@Value("${platform.security.worker-jwt.jwk-set-uri:}") String jwkSetUri,
			@Value("${platform.security.worker-jwt.media-audience:}") String mediaAudience,
			@Value("${platform.security.worker-jwt.export-audience:}") String exportAudience,
			@Value("${platform.security.worker-jwt.clock-skew:PT1M}") Duration clockSkew) {
		var requiredMediaAudience = requireText(mediaAudience, "Media Worker JWT audience");
		var requiredExportAudience = requireText(exportAudience, "Export Worker JWT audience");
		if (requiredMediaAudience.equals(requiredExportAudience)) {
			throw new IllegalArgumentException("Media and Export Worker JWT audiences must differ.");
		}
		return decoder(issuerUri, jwkSetUri, requiredExportAudience, clockSkew);
	}

	@Bean("aiWorkerJwtDecoder")
	JwtDecoder aiWorkerJwtDecoder(@Value("${platform.security.worker-jwt.issuer-uri:}") String issuerUri,
			@Value("${platform.security.worker-jwt.jwk-set-uri:}") String jwkSetUri,
			@Value("${platform.security.worker-jwt.media-audience:}") String mediaAudience,
			@Value("${platform.security.worker-jwt.export-audience:}") String exportAudience,
			@Value("${platform.security.worker-jwt.ai-audience:}") String aiAudience,
			@Value("${platform.security.worker-jwt.clock-skew:PT1M}") Duration clockSkew) {
		var requiredAiAudience = requireText(aiAudience, "AI Worker JWT audience");
		if (requiredAiAudience.equals(requireText(mediaAudience, "Media Worker JWT audience"))
				|| requiredAiAudience.equals(requireText(exportAudience, "Export Worker JWT audience"))) {
			throw new IllegalArgumentException("AI Worker JWT audience must differ from Media and Export Workers.");
		}
		return decoder(issuerUri, jwkSetUri, requiredAiAudience, clockSkew);
	}

	@Bean
	@Order(1)
	SecurityFilterChain mediaWorkerJwtSecurityFilterChain(HttpSecurity http,
			@Qualifier("mediaWorkerJwtDecoder") JwtDecoder decoder) throws Exception {
		return workerChain(http, "/api/v1/internal/media-jobs/**", decoder, MEDIA_WORKER_ROLE);
	}

	@Bean
	@Order(1)
	SecurityFilterChain exportWorkerJwtSecurityFilterChain(HttpSecurity http,
			@Qualifier("exportWorkerJwtDecoder") JwtDecoder decoder) throws Exception {
		return workerChain(http, "/api/v1/internal/dataset-exports/**", decoder, EXPORT_WORKER_ROLE);
	}

	@Bean
	@Order(1)
	SecurityFilterChain aiWorkerJwtSecurityFilterChain(HttpSecurity http,
			@Qualifier("aiWorkerJwtDecoder") JwtDecoder decoder) throws Exception {
		return workerChain(http, "/api/v1/internal/ai-jobs/**", decoder, AI_WORKER_ROLE);
	}

	static JwtDecoder decoder(String issuerUri, String jwkSetUri, String audience, Duration clockSkew) {
		var issuer = requireHttpsUri(issuerUri, "Worker JWT issuer URI");
		var jwks = requireHttpsUri(jwkSetUri, "Worker JWT JWK Set URI");
		var requiredAudience = requireText(audience, "Worker JWT audience");
		if (clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
			throw new IllegalArgumentException("Worker JWT clock skew must be between PT0S and PT5M.");
		}

		var decoder = NimbusJwtDecoder.withJwkSetUri(jwks.toString()).build();
		decoder.setJwtValidator(jwtValidator(issuer.toString(), requiredAudience, clockSkew));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> jwtValidator(String issuerUri, String audience, Duration clockSkew) {
		var timestamp = new JwtTimestampValidator(clockSkew);
		var issuer = new JwtIssuerValidator(issuerUri);
		OAuth2TokenValidator<Jwt> requiredAudience = jwt -> List.of(audience).equals(jwt.getAudience())
				? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
		OAuth2TokenValidator<Jwt> subject = jwt -> jwt.getSubject() == null || jwt.getSubject().isBlank()
				? OAuth2TokenValidatorResult.failure(MISSING_SUBJECT) : OAuth2TokenValidatorResult.success();
		return new DelegatingOAuth2TokenValidator<>(timestamp, issuer, requiredAudience, subject);
	}

	static Converter<Jwt, JwtAuthenticationToken> workerAuthenticationConverter(String role) {
		var converter = new JwtAuthenticationConverter();
		converter.setPrincipalClaimName("sub");
		converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority(role)));
		return jwt -> (JwtAuthenticationToken) converter.convert(jwt);
	}

	private static SecurityFilterChain workerChain(HttpSecurity http, String path, JwtDecoder decoder, String role)
			throws Exception {
		return http.securityMatcher(path)
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.authorizeHttpRequests(authorize -> authorize.anyRequest().hasAuthority(role))
			.oauth2ResourceServer(oauth2 -> oauth2
				.authenticationEntryPoint(WorkerJwtSecurityConfiguration::unauthorized)
				.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(workerAuthenticationConverter(role))))
			.build();
	}

	private static URI requireHttpsUri(String value, String label) {
		var uri = URI.create(requireText(value, label));
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
			throw new IllegalArgumentException(label + " must be an absolute HTTPS URI.");
		}
		return uri;
	}

	private static String requireText(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " must be configured.");
		}
		return value.trim();
	}

	private static void unauthorized(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.core.AuthenticationException failure) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader("WWW-Authenticate", "Bearer");
		response.setHeader("Cache-Control", "no-store");
		response.setContentType("application/problem+json");
		response.getWriter()
			.write("{\"error_code\":\"UNAUTHORIZED\","
					+ "\"error_message\":\"A valid Worker machine identity is required.\"," + "\"retryable\":false,"
					+ "\"request_id\":\"" + java.util.UUID.randomUUID() + "\"}");
	}

}
