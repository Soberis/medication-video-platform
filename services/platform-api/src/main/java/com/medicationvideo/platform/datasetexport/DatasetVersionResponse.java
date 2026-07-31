package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

record DatasetVersionResponse(UUID datasetVersionId, UUID projectId, String displayName, DatasetVersionStatus status,
		String createdBy, Instant createdAt) {

	static DatasetVersionResponse from(DatasetVersion version) {
		return new DatasetVersionResponse(version.getDatasetVersionId(), version.getProjectId(),
				version.getDisplayName(), version.getStatus(), version.getCreatedBy(), version.getCreatedAt());
	}
}
