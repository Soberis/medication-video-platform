package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
class MediaJobAttemptQueueService {

	private final MediaJobAttemptRepository attemptRepository;

	private final MediaJobDispatchPort dispatchPort;

	private final MediaLeaseCapabilityFactory capabilityFactory;

	MediaJobAttemptQueueService(MediaJobAttemptRepository attemptRepository, MediaJobDispatchPort dispatchPort,
			MediaLeaseCapabilityFactory capabilityFactory) {
		this.attemptRepository = attemptRepository;
		this.dispatchPort = dispatchPort;
		this.capabilityFactory = capabilityFactory;
	}

	void enqueue(MediaJob job, int attemptNumber, Instant requestedAt, Instant availableAt) {
		if (availableAt.isBefore(requestedAt)) {
			throw new IllegalArgumentException("A media job attempt cannot be available before it was requested.");
		}
		var messageId = UUID.randomUUID();
		var capability = this.capabilityFactory.create();
		this.attemptRepository.save(MediaJobAttempt.queue(job.getMediaJobId(), attemptNumber, messageId,
				capability.tokenHash(), requestedAt));
		this.dispatchPort.enqueue(new MediaProbeJobEnvelope("media-probe-job.v1", messageId, job.getMediaJobId(),
				job.getJobType(), attemptNumber, capability.token(), job.getProjectId(), job.getClipAssetId(),
				job.getInputDigest(), job.getInputSizeBytes(), job.getContentType(), job.getProbeProfileKey(),
				job.getRecipeDigest(), requestedAt), availableAt);
	}

}
