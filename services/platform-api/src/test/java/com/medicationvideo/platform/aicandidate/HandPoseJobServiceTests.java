package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class HandPoseJobServiceTests {

	@Test
	void refusesQueueingBeforeThePhaseGateIsExplicitlyEnabled() {
		var access = mock(ProjectAccessService.class);
		var service = new HandPoseJobService(access, mock(AssetCatalogQueryService.class),
				mock(AiCandidateJobRepository.class), mock(AiCandidateJobIdempotencyReceiptRepository.class),
				mock(AuditEventRecorder.class), mock(AiCandidateJobAttemptQueueService.class), "a".repeat(64), "EGO",
				false);

		var exception = assertThrows(HandPoseJobException.class, () -> service.queue(UUID.randomUUID(),
				UUID.randomUUID(), "actor", "idempotency-key-0001", "request", "trace"));

		assertEquals("AI_HAND_POSE_FEATURE_NOT_ENABLED", exception.getErrorCode());
		verifyNoInteractions(access);
	}

}
