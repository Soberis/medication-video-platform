package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

import com.medicationvideo.platform.iamproject.PlatformActor;

class HandPoseJobControllerTests {

	@Test
	void generatesAnAuditRequestIdWhenTheOptionalHeaderIsAbsent() {
		var service = mock(HandPoseJobService.class);
		var controller = new HandPoseJobController(service, mock(HandPoseCandidateQueryService.class));
		var projectId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var jobId = UUID.randomUUID();
		var authentication = mock(Authentication.class);
		when(authentication.getPrincipal()).thenReturn(new PlatformActor(projectId, "reviewer"));
		when(service.queue(eq(projectId), eq(clipAssetId), eq("reviewer"), any(), any(), any()))
			.thenReturn(new HandPoseJobResponse(jobId, projectId, clipAssetId, "MEDIAPIPE_HAND_LANDMARKER", "v1",
					"a".repeat(64), "b".repeat(64), 1L, 5, "c".repeat(64), "QUEUED", "reviewer", Instant.EPOCH,
					Instant.EPOCH));

		controller.queue(projectId, clipAssetId, "idempotency-key-0001", null, authentication);

		var requestId = ArgumentCaptor.forClass(String.class);
		org.mockito.Mockito.verify(service)
			.queue(eq(projectId), eq(clipAssetId), eq("reviewer"), eq("idempotency-key-0001"), requestId.capture(),
					any());
		assertNotNull(requestId.getValue());
		assertEquals(36, requestId.getValue().length());
	}

	@Test
	void delegatesManualRetryToTheDedicatedRetryApplicationService() {
		var service = mock(HandPoseJobService.class);
		var controller = new HandPoseJobController(service, mock(HandPoseCandidateQueryService.class));
		var projectId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var authentication = mock(Authentication.class);
		when(authentication.getPrincipal()).thenReturn(new PlatformActor(projectId, "reviewer"));
		when(service.retry(eq(projectId), eq(clipAssetId), eq("reviewer"), any(), any(), any()))
			.thenReturn(new HandPoseJobResponse(UUID.randomUUID(), projectId, clipAssetId, "MEDIAPIPE_HAND_LANDMARKER",
					"v1", "a".repeat(64), "b".repeat(64), 1L, 10, "c".repeat(64), "QUEUED", "reviewer", Instant.EPOCH,
					Instant.EPOCH));

		controller.retry(projectId, clipAssetId, "retry-idempotency-key-0001", "request-0001", authentication);

		org.mockito.Mockito.verify(service)
			.retry(eq(projectId), eq(clipAssetId), eq("reviewer"), eq("retry-idempotency-key-0001"), eq("request-0001"),
					any());
	}

}
