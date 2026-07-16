package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

record MediaJobResponse(
		UUID mediaJobId,
		UUID projectId,
		UUID clipAssetId,
		MediaJobType jobType,
		MediaJobStatus status,
		String inputDigest,
		long inputSizeBytes,
		String contentType,
		Instant createdAt,
		Instant updatedAt) {

	static MediaJobResponse from(MediaJob job) {
		return new MediaJobResponse(
				job.getMediaJobId(),
				job.getProjectId(),
				job.getClipAssetId(),
				job.getJobType(),
				job.getStatus(),
				job.getInputDigest(),
				job.getInputSizeBytes(),
				job.getContentType(),
				job.getCreatedAt(),
				job.getUpdatedAt());
	}
}
