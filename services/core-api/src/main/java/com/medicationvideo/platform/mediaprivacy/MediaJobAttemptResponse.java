package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;

record MediaJobAttemptResponse(int attempt, MediaJobAttemptStatus status, Instant leaseUntil, Instant lastHeartbeatAt,
		Instant startedAt, Instant completedAt, MediaJobErrorResponse error) {

	static MediaJobAttemptResponse from(MediaJobAttempt attempt) {
		MediaJobErrorResponse error = null;
		if (attempt.getErrorCode() != null) {
			error = new MediaJobErrorResponse(attempt.getErrorClass(), attempt.getErrorCode(),
					attempt.getSafeErrorMessage(), Boolean.TRUE.equals(attempt.getErrorRetryable()));
		}
		return new MediaJobAttemptResponse(attempt.getAttempt(), attempt.getStatus(), attempt.getLeaseUntil(),
				attempt.getLastHeartbeatAt(), attempt.getStartedAt(), attempt.getCompletedAt(), error);
	}
}
