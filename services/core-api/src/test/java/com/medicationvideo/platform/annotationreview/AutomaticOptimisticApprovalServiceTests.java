package com.medicationvideo.platform.annotationreview;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.medicationvideo.platform.aicandidate.ActionCandidateJobCompletedEvent;
import com.medicationvideo.platform.aicandidate.HandPoseJobCompletedEvent;

@SpringBootTest
@ActiveProfiles("test")
class AutomaticOptimisticApprovalServiceTests {

	@Autowired
	private AutomaticOptimisticApprovalService service;

	@Test
	void contextLoadsAndServiceBeanExists() {
		assertNotNull(this.service);
	}

	@Test
	void safeWithNullParameters() {
		assertDoesNotThrow(() -> this.service.adoptCaptureGroupIfEligible(null, null));
	}

	@Test
	void reconcileRunsSafely() {
		assertDoesNotThrow(() -> this.service.reconcile());
	}

	@Test
	void eventListenersHandleEventsSafely() {
		var projectId = UUID.randomUUID();
		var captureGroupId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();

		assertDoesNotThrow(() -> this.service
			.onActionCandidateCompleted(new ActionCandidateJobCompletedEvent(projectId, captureGroupId, clipAssetId)));
		assertDoesNotThrow(() -> this.service
			.onHandPoseCompleted(new HandPoseJobCompletedEvent(projectId, captureGroupId, clipAssetId)));
	}

}
