package com.medicationvideo.platform.datasetexport;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class DatasetExportRetryPolicy {

	private final int maxAttempts;

	private final Duration baseDelay;

	private final Duration maxDelay;

	DatasetExportRetryPolicy(@Value("${platform.dataset-export.max-attempts:3}") int maxAttempts,
			@Value("${platform.dataset-export.retry-base-delay:PT5S}") Duration baseDelay,
			@Value("${platform.dataset-export.retry-max-delay:PT5M}") Duration maxDelay) {
		if (maxAttempts < 1 || maxAttempts > 10) {
			throw new IllegalArgumentException("Dataset export max attempts must be between 1 and 10.");
		}
		if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
			throw new IllegalArgumentException("Dataset export retry delays are invalid.");
		}
		this.maxAttempts = maxAttempts;
		this.baseDelay = baseDelay;
		this.maxDelay = maxDelay;
	}

	boolean shouldRetry(DatasetExportErrorPayload error, int attempt) {
		return error.retryable() && hasAttemptsRemaining(attempt)
				&& (error.errorClass() == DatasetExportErrorClass.RETRYABLE_TRANSIENT
						|| error.errorClass() == DatasetExportErrorClass.RETRYABLE_RESOURCE);
	}

	boolean hasAttemptsRemaining(int completedAttempt) {
		return completedAttempt < this.maxAttempts;
	}

	Instant nextAvailableAt(int nextAttempt, Instant now) {
		if (nextAttempt < 2 || nextAttempt > this.maxAttempts) {
			throw new IllegalArgumentException("The next dataset export attempt is outside the retry policy.");
		}
		var multiplier = 1L << Math.min(nextAttempt - 2, 20);
		var delay = this.baseDelay.multipliedBy(multiplier);
		return now.plus(delay.compareTo(this.maxDelay) > 0 ? this.maxDelay : delay);
	}

}
