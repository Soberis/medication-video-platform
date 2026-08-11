package com.medicationvideo.platform.aicandidate;

final class HandPoseRetryPolicy {

	private HandPoseRetryPolicy() {
	}

	static boolean isRetryable(String failureReason) {
		return "AI_HAND_POSE_INFERENCE_FAILED".equals(failureReason)
				|| "AI_HAND_POSE_INVALID_OUTPUT".equals(failureReason);
	}

	static boolean isRetryable(String failureReason, int attempt, int maxAttempts) {
		return isRetryable(failureReason) && attempt > 0 && attempt < maxAttempts;
	}

	static void validateMaxAttempts(int maxAttempts) {
		if (maxAttempts < 1 || maxAttempts > 10) {
			throw new IllegalArgumentException("Hand pose max attempts must be between 1 and 10.");
		}
	}

}
