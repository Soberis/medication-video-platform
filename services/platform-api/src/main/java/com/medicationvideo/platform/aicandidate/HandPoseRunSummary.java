package com.medicationvideo.platform.aicandidate;

import java.time.Instant;

record HandPoseRunSummary(boolean analysisComplete, String handResult, Long totalVideoFrames, Long inferenceFrameCount,
		Long framesWithHand, Long framesWithoutHand, Long leftHandDetectionCount, Long rightHandDetectionCount,
		Long invalidLandmarkCount, Long processingDurationMs, Instant startedAt, Instant completedAt,
		String failureReason) {

	static HandPoseRunSummary from(AiCandidateJobAttempt attempt) {
		return new HandPoseRunSummary(attempt.isAnalysisComplete(), attempt.getHandResult(),
				attempt.getTotalVideoFrames(), attempt.getInferenceFrameCount(), attempt.getFramesWithHand(),
				attempt.getFramesWithoutHand(), attempt.getLeftHandDetectionCount(),
				attempt.getRightHandDetectionCount(), attempt.getInvalidLandmarkCount(),
				attempt.getProcessingDurationMs(), attempt.getStartedAt(), attempt.getCompletedAt(),
				attempt.getFailureReason());
	}
}
