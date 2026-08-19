package com.medicationvideo.platform.iamproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class WorkerJwtSecurityConfigurationTests {

	private static final String ISSUER = "https://identity.example.test/worker";

	private static final String MEDIA_AUDIENCE = "medication-platform-media-worker";

	private static final String UNEXPECTED_AUDIENCE = "unexpected-worker-audience";

	@Test
	void acceptsOnlyTheExpectedIssuerAudienceSubjectAndValidityWindow() {
		var validator = WorkerJwtSecurityConfiguration.jwtValidator(ISSUER, MEDIA_AUDIENCE, Duration.ofSeconds(30));

		assertFalse(validator.validate(jwt(ISSUER, MEDIA_AUDIENCE, "media-worker-1", 120)).hasErrors());
		assertTrue(validator.validate(jwt("https://other.example.test", MEDIA_AUDIENCE, "media-worker-1", 120))
			.hasErrors());
		assertTrue(validator.validate(jwt(ISSUER, UNEXPECTED_AUDIENCE, "media-worker-1", 120)).hasErrors());
		assertTrue(validator.validate(jwt(ISSUER, List.of(MEDIA_AUDIENCE, UNEXPECTED_AUDIENCE), "media-worker-1", 120))
			.hasErrors());
		assertTrue(validator.validate(jwt(ISSUER, MEDIA_AUDIENCE, null, 120)).hasErrors());
		assertTrue(validator.validate(jwt(ISSUER, MEDIA_AUDIENCE, "media-worker-1", -120)).hasErrors());
	}

	@Test
	void mapsAValidatedJwtToExactlyOneWorkerRole() {
		var authentication = WorkerJwtSecurityConfiguration.workerAuthenticationConverter("ROLE_MEDIA_WORKER")
			.convert(jwt(ISSUER, MEDIA_AUDIENCE, "media-worker-1", 120));

		assertNotNull(authentication);
		assertEquals("media-worker-1", authentication.getName());
		var authorities = authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).toList();
		assertTrue(authorities.contains("ROLE_MEDIA_WORKER"));
	}

	@Test
	void rejectsMissingOrInsecureProductionConfigurationBeforeServingRequests() {
		assertThrows(IllegalArgumentException.class, () -> WorkerJwtSecurityConfiguration.decoder("",
				"https://identity.example.test/jwks", MEDIA_AUDIENCE, Duration.ofMinutes(1)));
		assertThrows(IllegalArgumentException.class, () -> WorkerJwtSecurityConfiguration.decoder(ISSUER,
				"http://identity.example.test/jwks", MEDIA_AUDIENCE, Duration.ofMinutes(1)));
		assertThrows(IllegalArgumentException.class, () -> WorkerJwtSecurityConfiguration.decoder(ISSUER,
				"https://identity.example.test/jwks", MEDIA_AUDIENCE, Duration.ofMinutes(6)));

	}

	private static Jwt jwt(String issuer, String audience, String subject, long expiresInSeconds) {
		return jwt(issuer, List.of(audience), subject, expiresInSeconds);
	}

	private static Jwt jwt(String issuer, List<String> audience, String subject, long expiresInSeconds) {
		var now = Instant.now();
		var issuedAt = expiresInSeconds < 0 ? now.minusSeconds(Math.abs(expiresInSeconds) + 60) : now.minusSeconds(5);
		var builder = Jwt.withTokenValue("synthetic-signed-token")
			.header("alg", "RS256")
			.issuer(issuer)
			.audience(audience)
			.issuedAt(issuedAt)
			.notBefore(issuedAt)
			.expiresAt(now.plusSeconds(expiresInSeconds));
		if (subject != null) {
			builder.subject(subject);
		}
		return builder.build();
	}

}
