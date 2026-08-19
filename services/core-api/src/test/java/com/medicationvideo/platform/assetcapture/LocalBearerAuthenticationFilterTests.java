package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LocalBearerAuthenticationFilterTests {

	private final LocalBearerAuthenticationFilter filter = new LocalBearerAuthenticationFilter("u".repeat(32),
			"a".repeat(32), UUID.randomUUID());

	@Test
	void leavesAiWorkerRequestsToTheDedicatedSecurityChain() {
		var request = new MockHttpServletRequest("POST", "/api/v1/internal/ai-jobs/123/attempts/1/lease");

		assertTrue(this.filter.shouldNotFilter(request));
	}

	@Test
	void stillAppliesToPlatformApiRequests() {
		var request = new MockHttpServletRequest("POST", "/api/v1/projects/123/clip-assets");

		assertFalse(this.filter.shouldNotFilter(request));
	}

}
