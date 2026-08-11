package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
class AiCandidateJobAttemptQueueService {

	private final AiCandidateJobAttemptRepository attemptRepository;

	private final AiJobDispatchPort dispatchPort;

	private final AiLeaseCapabilityFactory capabilityFactory;

	AiCandidateJobAttemptQueueService(AiCandidateJobAttemptRepository attemptRepository, AiJobDispatchPort dispatchPort,
			AiLeaseCapabilityFactory capabilityFactory) {
		this.attemptRepository = attemptRepository;
		this.dispatchPort = dispatchPort;
		this.capabilityFactory = capabilityFactory;
	}

	void enqueue(AiCandidateJob job, int attempt, Instant requestedAt) {
		var messageId = UUID.randomUUID();
		var capability = this.capabilityFactory.create();
		this.attemptRepository.save(
				AiCandidateJobAttempt.queue(job.getAiJobId(), attempt, messageId, capability.tokenHash(), requestedAt));
		this.dispatchPort.enqueue(new AiHandPoseJobEnvelope("ai-hand-pose-job.v1", messageId, job.getAiJobId(), attempt,
				capability.token(), job.getProjectId(), job.getClipAssetId(), job.getInputSha256(),
				job.getInputSizeBytes(), job.getContentType(), job.getAlgorithmKey(), job.getAlgorithmVersion(),
				job.getModelSha256(), job.getSamplingFps(), job.getProfileSha256(), requestedAt), requestedAt);
	}

	AiCandidateJobAttempt latest(UUID jobId) {
		return this.attemptRepository.findLatestForUpdate(jobId).stream().findFirst().orElse(null);
	}

}
