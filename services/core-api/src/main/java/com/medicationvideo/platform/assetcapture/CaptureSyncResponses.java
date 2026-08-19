package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Map;
import java.util.UUID;

record CaptureSyncTimelineResponse(UUID captureId, String syncStatus, UUID currentSyncRevisionId,
		String latestJobStatus, String timeBasisSource, String captureOriginUtc, Long timelineRateNumerator,
		Long timelineRateDenominator, Long timelineStepNumeratorNs, Long timelineStepDenominator, Long syncFrameCount,
		Long firstCaptureTimestampNs, Long lastCaptureTimestampNs,
		List<CaptureSyncExpectedCameraResponse> expectedCameras, CaptureSyncTimelineSummaryResponse summary) {

	static CaptureSyncTimelineResponse withoutRevision(UUID captureSessionId, String syncStatus,
			String latestJobStatus) {
		return new CaptureSyncTimelineResponse(captureSessionId, syncStatus, null, latestJobStatus, null, null, null,
				null, null, null, null, null, null, List.of(), null);
	}

	static CaptureSyncTimelineResponse fromRevision(UUID captureSessionId, CaptureSyncRevision revision,
			List<CaptureSyncStreamRevision> streams, String latestJobStatus) {
		var expectedCameras = streams.stream().map(CaptureSyncExpectedCameraResponse::from).toList();
		var summary = new CaptureSyncTimelineSummaryResponse(revision.getPresentFrameCount(),
				revision.getMissingFrameCount(), revision.getInvalidFrameCount(), revision.getMaxSyncErrorNs(),
				revision.getP95SyncErrorNs());
		var firstTimestamp = 0L;
		var lastTimestamp = revision.getSyncFrameCount() <= 0 ? null : (revision.getSyncFrameCount() - 1)
				* revision.getTimelineStepNumeratorNs() / revision.getTimelineStepDenominator();
		return new CaptureSyncTimelineResponse(captureSessionId, "SYNCHRONIZED", revision.getCaptureSyncRevisionId(),
				latestJobStatus, revision.getTimeBasisSource(),
				revision.getCaptureOriginUtc() == null ? null : revision.getCaptureOriginUtc().toString(),
				revision.getTimelineRateNumerator(), revision.getTimelineRateDenominator(),
				revision.getTimelineStepNumeratorNs(), revision.getTimelineStepDenominator(),
				revision.getSyncFrameCount(), firstTimestamp, lastTimestamp, expectedCameras, summary);
	}
}

record CaptureSyncExpectedCameraResponse(int cameraNumber, String streamPresenceState, UUID cameraStreamId,
		UUID videoId) {

	static CaptureSyncExpectedCameraResponse from(CaptureSyncStreamRevision stream) {
		return new CaptureSyncExpectedCameraResponse(stream.getCameraNumber(), stream.getStreamPresenceState().name(),
				stream.getCameraStreamId(), stream.getClipAssetId());
	}
}

record CaptureSyncTimelineSummaryResponse(long presentFrameCount, long missingFrameCount, long invalidFrameCount,
		Long maxSyncErrorNs, Long p95SyncErrorNs) {
}

record CaptureSyncFramePageResponse(UUID syncRevisionId,
		List<SynchronizedFrameQueryPort.SynchronizedFrameSlotResponse> items, String nextCursor) {
}

record CaptureSyncJobResponse(UUID captureSyncJobId, UUID captureSessionId, String status, String syncProfileKey,
		String syncProfileVersion, String inputManifestSha256, String latestAttemptStatus, int latestAttempt,
		String statusUrl) {

	static CaptureSyncJobResponse from(CaptureSyncJob job, CaptureSyncJobAttempt attempt, UUID projectId) {
		return new CaptureSyncJobResponse(job.getCaptureSyncJobId(), job.getCaptureSessionId(), job.getStatus().name(),
				job.getSyncProfileKey(), job.getSyncProfileVersion(), job.getInputManifestSha256(),
				attempt.getStatus().name(), attempt.getAttempt(), "/api/v1/projects/" + projectId + "/capture-sessions/"
						+ job.getCaptureSessionId() + "/synchronizations/" + job.getCaptureSyncJobId());
	}
}

record CaptureSyncStartRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(
		regexp = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$") String syncProfileKey) {
}

record CaptureSyncProvenanceResponse(UUID captureSessionId, UUID syncRevisionId, long syncFrameIndex, int cameraNumber,
		Long sourceFrameIndex, Long sourcePtsNs, Integer sourcePtsOrdinal, Long syncErrorNs, String originalMp4Sha256,
		UUID cameraStreamId, UUID videoId, UUID sourceMediaReportId, Map<String, Object> revisionRefs) {
}
