package com.medicationvideo.platform.aicandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
class AiLeaseCapabilityFactory {

	private final SecureRandom secureRandom = new SecureRandom();

	Capability create() {
		var bytes = new byte[32];
		this.secureRandom.nextBytes(bytes);
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new Capability(token, sha256(token));
	}

	static String sha256(String token) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

	record Capability(String token, String tokenHash) {
	}

}
