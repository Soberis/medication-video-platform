package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;

record MediaJobAttemptResponse(
		int attempt,
		MediaJobStatus status,
		Instant startedAt,
		Instant completedAt,
		MediaJobErrorResponse error) {

	static MediaJobAttemptResponse from(MediaJobAttempt attempt) {
		MediaJobErrorResponse error = null;
		if (attempt.getErrorCode() != null) {
			error = new MediaJobErrorResponse(
					attempt.getErrorClass(),
					attempt.getErrorCode(),
					attempt.getSafeErrorMessage(),
					Boolean.TRUE.equals(attempt.getErrorRetryable()));
		}
		return new MediaJobAttemptResponse(
				attempt.getAttempt(),
				attempt.getStatus(),
				attempt.getStartedAt(),
				attempt.getCompletedAt(),
				error);
	}
}
