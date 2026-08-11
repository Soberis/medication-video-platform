package com.medicationvideo.platform.aicandidate;

final class HandPoseRetryPolicy {

	private HandPoseRetryPolicy() {
	}

	static boolean isRetryable(String failureReason) {
		return "AI_HAND_POSE_INFERENCE_FAILED".equals(failureReason)
				|| "AI_HAND_POSE_INVALID_OUTPUT".equals(failureReason);
	}

}
