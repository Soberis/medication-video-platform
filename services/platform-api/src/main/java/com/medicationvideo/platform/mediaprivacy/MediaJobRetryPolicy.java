package com.medicationvideo.platform.mediaprivacy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MediaJobRetryPolicy {

	private final int maxAttempts;
	private final Duration baseDelay;
	private final Duration maxDelay;

	MediaJobRetryPolicy(
			@Value("${platform.media-probe.max-attempts:3}") int maxAttempts,
			@Value("${platform.media-probe.retry-base-delay:PT5S}") Duration baseDelay,
			@Value("${platform.media-probe.retry-max-delay:PT5M}") Duration maxDelay) {
		if (maxAttempts < 1 || maxAttempts > 20) {
			throw new IllegalArgumentException(
					"Media probe max attempts must be between 1 and 20.");
		}
		this.baseDelay = requireAtLeastOneSecond(baseDelay, "Media probe retry base delay");
		this.maxDelay = requireAtLeastOneSecond(maxDelay, "Media probe retry max delay");
		if (this.maxDelay.compareTo(this.baseDelay) < 0) {
			throw new IllegalArgumentException(
					"Media probe retry max delay must not be shorter than the base delay.");
		}
		this.maxAttempts = maxAttempts;
	}

	boolean shouldRetry(MediaProbeErrorPayload error, int completedAttempt) {
		return Boolean.TRUE.equals(error.retryable()) && hasAttemptsRemaining(completedAttempt);
	}

	boolean hasAttemptsRemaining(int completedAttempt) {
		return completedAttempt < this.maxAttempts;
	}

	Instant nextAvailableAt(UUID mediaJobId, int nextAttempt, Instant now) {
		if (nextAttempt < 2 || nextAttempt > this.maxAttempts) {
			throw new IllegalArgumentException("The next media job attempt is outside the retry policy.");
		}
		var baseMillis = this.baseDelay.toMillis();
		var maxMillis = this.maxDelay.toMillis();
		var shift = Math.min(nextAttempt - 2, 62);
		var exponentialMillis = baseMillis > (maxMillis >> shift)
				? maxMillis
				: Math.min(maxMillis, baseMillis << shift);
		var jitterWindow = Math.max(1L, exponentialMillis / 4L);
		var seed = mediaJobId.getMostSignificantBits()
				^ mediaJobId.getLeastSignificantBits()
				^ nextAttempt;
		var jitterMillis = Math.floorMod(seed, jitterWindow + 1L);
		return now.plusMillis(Math.min(maxMillis, exponentialMillis + jitterMillis));
	}

	private static Duration requireAtLeastOneSecond(Duration value, String label) {
		if (value.isNegative() || value.toMillis() < 1000) {
			throw new IllegalArgumentException(label + " must be at least one second.");
		}
		return value;
	}
}
