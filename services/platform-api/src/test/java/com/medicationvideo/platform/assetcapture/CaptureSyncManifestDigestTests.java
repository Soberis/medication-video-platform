package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CaptureSyncManifestDigestTests {

	@Test
	void verifiesWorkerCompatibleCanonicalJsonAndRejectsTampering() {
		var expectedDigest = sha256("{\"nested\":{\"a\":1,\"z\":2},\"schema_version\":\"synchronized-frame.v1\"}");
		var manifest = new LinkedHashMap<String, Object>();
		manifest.put("schema_version", "synchronized-frame.v1");
		manifest.put("nested", Map.of("z", 2, "a", 1));
		manifest.put("artifact_manifest_sha256", expectedDigest);

		assertDoesNotThrow(() -> CaptureSyncExecutionService.validateManifestDigest(manifest, expectedDigest));

		manifest.put("nested", Map.of("z", 3, "a", 1));
		var exception = assertThrows(CaptureSyncExecutionException.class,
				() -> CaptureSyncExecutionService.validateManifestDigest(manifest, expectedDigest));
		assertEquals("CAPTURE_SYNC_MANIFEST_DIGEST_MISMATCH", exception.getErrorCode());
	}

	@Test
	void rejectsAnEmbeddedDigestThatDiffersFromTheResultEnvelope() {
		var manifest = Map.<String, Object>of("schema_version", "synchronized-frame.v1", "artifact_manifest_sha256",
				"b".repeat(64));
		var exception = assertThrows(CaptureSyncExecutionException.class,
				() -> CaptureSyncExecutionService.validateManifestDigest(manifest, "a".repeat(64)));
		assertEquals("CAPTURE_SYNC_MANIFEST_INVALID", exception.getErrorCode());
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

}
