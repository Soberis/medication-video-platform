package com.medicationvideo.platform.aicandidate;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.medicationvideo.platform.assetcapture.HandPoseSchedulingPort;

/**
 * Best-effort automatic hand-pose scheduling after the ClipAsset INGESTED transaction
 * commits. AI is auxiliary: its configuration, capacity or dispatch failure must never
 * change the already committed media state.
 */
@Service
class HandPoseAutoSchedulingService implements HandPoseSchedulingPort {

	private static final Logger LOGGER = LoggerFactory.getLogger(HandPoseAutoSchedulingService.class);

	private final HandPoseJobService handPoseJobService;

	private final ActionCandidateJobService actionCandidateJobService;

	private final TransactionTemplate requiresNewTransaction;

	HandPoseAutoSchedulingService(HandPoseJobService handPoseJobService,
			PlatformTransactionManager transactionManager) {
		this(handPoseJobService, null, transactionManager);
	}

	@Autowired
	HandPoseAutoSchedulingService(HandPoseJobService handPoseJobService,
			ActionCandidateJobService actionCandidateJobService, PlatformTransactionManager transactionManager) {
		this.handPoseJobService = handPoseJobService;
		this.actionCandidateJobService = actionCandidateJobService;
		this.requiresNewTransaction = new TransactionTemplate(transactionManager);
		this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public void scheduleAfterMediaIngested(UUID projectId, UUID clipAssetId) {
		var callback = new Runnable() {
			@Override
			public void run() {
				scheduleInNewTransaction(projectId, clipAssetId);
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

				@Override
				public void afterCommit() {
					callback.run();
				}

			});
		}
		else {
			callback.run();
		}
	}

	private void scheduleInNewTransaction(UUID projectId, UUID clipAssetId) {
		try {
			this.requiresNewTransaction.executeWithoutResult(status -> {
				this.handPoseJobService.queueAfterMediaIngested(projectId, clipAssetId);
			});
		}
		catch (HandPoseJobException exception) {
			LOGGER.info("Automatic hand-pose scheduling skipped for project {} clip asset {}: {}", projectId,
					clipAssetId, exception.getErrorCode());
		}
		catch (RuntimeException exception) {
			LOGGER.warn(
					"Automatic hand-pose scheduling failed for project {} clip asset {} without changing INGESTED state",
					projectId, clipAssetId, exception);
		}
		if (this.actionCandidateJobService == null) {
			return;
		}
		try {
			this.requiresNewTransaction.executeWithoutResult(status -> {
				this.actionCandidateJobService.queueAfterMediaIngested(projectId, clipAssetId);
			});
		}
		catch (HandPoseJobException exception) {
			LOGGER.info("Automatic action-candidate scheduling skipped for project {} clip asset {}: {}", projectId,
					clipAssetId, exception.getErrorCode());
		}
		catch (RuntimeException exception) {
			LOGGER.warn(
					"Automatic action-candidate scheduling failed for project {} clip asset {} without changing INGESTED state",
					projectId, clipAssetId, exception);
		}
	}

}
