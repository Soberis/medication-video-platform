package com.medicationvideo.platform.aicandidate;

/**
 * Keeps the P0 single-result contract bounded without inventing a video-length policy.
 * The bound is derived from the verified media duration and the frozen
 * sampling/hand-count profile.
 */
final class HandPoseCapacityGuard {

	private static final long MICROS_PER_SECOND = 1_000_000L;

	private HandPoseCapacityGuard() {
	}

	static long maximumCandidateCount(long durationMicroseconds, int samplingFps, int maxHands) {
		if (durationMicroseconds < 0 || samplingFps < 1 || maxHands < 1) {
			throw new IllegalArgumentException("Hand pose capacity inputs must be positive.");
		}
		final long sampledFrames;
		try {
			var numerator = Math.multiplyExact(durationMicroseconds, samplingFps);
			var wholeSeconds = numerator / MICROS_PER_SECOND;
			var remainder = numerator % MICROS_PER_SECOND;
			sampledFrames = remainder == 0 ? wholeSeconds : Math.addExact(wholeSeconds, 1L);
		}
		catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
		if (sampledFrames > Long.MAX_VALUE / maxHands) {
			return Long.MAX_VALUE;
		}
		return sampledFrames * maxHands;
	}

	static void requireWithinP0ResultLimit(long durationMicroseconds, int samplingFps, int maxHands) {
		if (maximumCandidateCount(durationMicroseconds, samplingFps, maxHands) > HandPoseProfile.MAX_CANDIDATES) {
			throw new HandPoseJobException("AI_HAND_POSE_INPUT_EXCEEDS_P0_RESULT_LIMIT",
					"This video is longer than the current single-video hand detection capacity.", 409);
		}
	}

}
