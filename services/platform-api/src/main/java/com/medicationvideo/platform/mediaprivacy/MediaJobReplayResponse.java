package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

record MediaJobReplayResponse(
		UUID replayId,
		UUID mediaJobId,
		UUID deadLetterId,
		int sourceAttempt,
		int newAttempt,
		MediaJobStatus resultingJobStatus,
		Instant createdAt) {

	static MediaJobReplayResponse from(MediaJobManualReplay replay) {
		return new MediaJobReplayResponse(
				replay.getReplayId(),
				replay.getMediaJobId(),
				replay.getDeadLetterId(),
				replay.getSourceAttempt(),
				replay.getNewAttempt(),
				MediaJobStatus.QUEUED,
				replay.getCreatedAt());
	}
}
