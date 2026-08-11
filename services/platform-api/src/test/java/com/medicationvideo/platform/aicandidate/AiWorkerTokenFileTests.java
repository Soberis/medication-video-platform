package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AiWorkerTokenFileTests {

	@Test
	void resolvesTheFileBackedIdentityWithoutPuttingTheFilePathInTheFilter() throws Exception {
		var token = "synthetic-ai-worker-file-token-do-not-reuse";
		var file = Files.createTempFile("ai-worker-token-", ".txt");
		try {
			Files.writeString(file, token + System.lineSeparator());
			assertEquals(token, AiWorkerSecurityConfiguration.resolveBearerToken("", file.toString()));
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void rejectsTwoConfiguredIdentitySources() {
		assertThrows(IllegalStateException.class, () -> AiWorkerSecurityConfiguration.resolveBearerToken("direct",
				Path.of("token.txt").toAbsolutePath().toString()));
	}

}
