package com.medicationvideo.platform.aicandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class HandPoseProfile {

	static final String ALGORITHM_KEY = "MEDIAPIPE_HAND_LANDMARKER";

	static final String ALGORITHM_VERSION = "v1";

	static final int SAMPLING_FPS = 10;

	private HandPoseProfile() {
	}

	static String fingerprint(String modelSha256) {
		return sha256(String.join("\n", ALGORITHM_KEY, ALGORITHM_VERSION, modelSha256, Integer.toString(SAMPLING_FPS)));
	}

	static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

}
