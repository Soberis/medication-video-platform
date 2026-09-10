package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
class AiCandidateJobAttemptQueueService {

	private final AiCandidateJobAttemptRepository attemptRepository;

	private final AiJobDispatchPort dispatchPort;

	private final AiLeaseCapabilityFactory capabilityFactory;

	private final ActionJobEnvelopeFactory actionEnvelopeFactory;

	AiCandidateJobAttemptQueueService(AiCandidateJobAttemptRepository attemptRepository, AiJobDispatchPort dispatchPort,
			AiLeaseCapabilityFactory capabilityFactory, ActionJobEnvelopeFactory actionEnvelopeFactory) {
		this.attemptRepository = attemptRepository;
		this.dispatchPort = dispatchPort;
		this.capabilityFactory = capabilityFactory;
		this.actionEnvelopeFactory = actionEnvelopeFactory;
	}

	void enqueue(AiCandidateJob job, int attempt, Instant requestedAt) {
		var messageId = UUID.randomUUID();
		var capability = this.capabilityFactory.create();
		this.attemptRepository.save(
				AiCandidateJobAttempt.queue(job.getAiJobId(), attempt, messageId, capability.tokenHash(), requestedAt));
		if (AiCandidateJob.ACTION_ALGORITHM_KEY.equals(job.getAlgorithmKey())) {
			this.dispatchActionAttempt(job, attempt, messageId, capability.token(), requestedAt);
			return;
		}
		this.dispatchPort.enqueue(new AiHandPoseJobEnvelope("ai-hand-pose-job.v1", messageId, job.getAiJobId(), attempt,
				capability.token(), job.getProjectId(), job.getClipAssetId(), job.getInputSha256(),
				job.getInputSizeBytes(), job.getContentType(), job.getAlgorithmKey(), job.getAlgorithmVersion(),
				job.getModelSha256(), job.getSamplingFps(), job.getProfileSha256(), requestedAt), requestedAt);
	}

	/**
	 * Retried action attempts must be republished as {@code ai-action-job.v1}; publishing
	 * them through the hand-pose envelope makes the Worker dead-letter them as a contract
	 * violation and leaves the job stuck in QUEUED.
	 */
	private void dispatchActionAttempt(AiCandidateJob job, int attempt, UUID messageId, String leaseToken,
			Instant requestedAt) {
		var captureGroupId = this.actionEnvelopeFactory.resolveCaptureGroupId(job.getClipAssetId());
		if (captureGroupId == null) {
			throw new HandPoseJobException("AI_ACTION_SOURCE_INVALID",
					"The source clip does not belong to a capture group.", 409);
		}
		this.dispatchPort.enqueueAction(
				this.actionEnvelopeFactory.create(job, captureGroupId, attempt, messageId, leaseToken, requestedAt),
				requestedAt);
	}

	AiCandidateJobAttempt latest(UUID jobId) {
		return this.attemptRepository.findLatestForUpdate(jobId).stream().findFirst().orElse(null);
	}

	AiCandidateJobAttempt latestReadOnly(UUID jobId) {
		return this.attemptRepository.findFirstByAiJobIdOrderByAttemptDesc(jobId).orElse(null);
	}

}
