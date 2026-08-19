package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

record MediaJobDeliveryRecoveryResponse(UUID recoveryId, UUID mediaJobId, UUID sourceMessageId, int sourceAttempt,
		int newAttempt, MediaJobStatus resultingJobStatus, Instant createdAt) {

	static MediaJobDeliveryRecoveryResponse from(MediaJobDeliveryRecovery recovery) {
		return new MediaJobDeliveryRecoveryResponse(recovery.getRecoveryId(), recovery.getMediaJobId(),
				recovery.getSourceMessageId(), recovery.getSourceAttempt(), recovery.getNewAttempt(),
				MediaJobStatus.QUEUED, recovery.getCreatedAt());
	}

}
