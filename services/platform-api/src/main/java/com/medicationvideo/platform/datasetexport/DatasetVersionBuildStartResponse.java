package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

record DatasetVersionBuildStartResponse(UUID datasetVersionId, DatasetVersionStatus status, String startedBy,
		Instant startedAt) {

	static DatasetVersionBuildStartResponse from(DatasetVersion version) {
		return new DatasetVersionBuildStartResponse(version.getDatasetVersionId(), version.getStatus(),
				version.getBuildStartedBy(), version.getBuildStartedAt());
	}
}
