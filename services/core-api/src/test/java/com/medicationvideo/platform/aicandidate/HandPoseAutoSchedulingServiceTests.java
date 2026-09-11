package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HandPoseAutoSchedulingServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void schedulesOnlyAfterTheIngestTransactionCommits() {
		var jobService = mock(HandPoseJobService.class);
		var transactionManager = transactionManager();
		var service = new HandPoseAutoSchedulingService(jobService, transactionManager);

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.scheduleAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);

			verifyNoInteractions(jobService);
			TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

			verify(jobService).queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void schedulesActionCandidatesWithTheSamePostCommitBoundary() {
		var handPoseJobService = mock(HandPoseJobService.class);
		var actionJobService = mock(ActionCandidateJobService.class);
		var service = new HandPoseAutoSchedulingService(handPoseJobService, actionJobService, transactionManager());

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.scheduleAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);

			verifyNoInteractions(handPoseJobService, actionJobService);
			TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

			verify(handPoseJobService).queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
			verify(actionJobService).queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void swallowsExpectedAutomaticEligibilityFailures() {
		var jobService = mock(HandPoseJobService.class);
		doThrow(new HandPoseJobException("AI_HAND_POSE_FEATURE_NOT_ENABLED", "disabled", 409)).when(jobService)
			.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
		var service = new HandPoseAutoSchedulingService(jobService, transactionManager());

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.scheduleAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);

			assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit());
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void swallowsUnexpectedDispatchFailuresWithoutPropagatingIntoMediaCompletion() {
		var jobService = mock(HandPoseJobService.class);
		doThrow(new IllegalStateException("dispatch unavailable")).when(jobService)
			.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
		var service = new HandPoseAutoSchedulingService(jobService, transactionManager());

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.scheduleAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);

			assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit());
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private static PlatformTransactionManager transactionManager() {
		var transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
			.thenReturn(mock(TransactionStatus.class));
		return transactionManager;
	}

}
