package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

record DatasetBuildSnapshotResponse(UUID datasetBuildSnapshotId, UUID datasetVersionId,
		UUID datasetBuildSpecificationId, UUID datasetSplitPlanId, String snapshotSchemaVersion,
		String inputVariantPolicy, int memberCount, int streamCount, String snapshotDigest, String createdBy,
		Instant createdAt) {

	static DatasetBuildSnapshotResponse from(DatasetBuildSnapshot snapshot) {
		return new DatasetBuildSnapshotResponse(snapshot.getDatasetBuildSnapshotId(), snapshot.getDatasetVersionId(),
				snapshot.getDatasetBuildSpecificationId(), snapshot.getDatasetSplitPlanId(),
				snapshot.getSnapshotSchemaVersion(), snapshot.getInputVariantPolicy(), snapshot.getMemberCount(),
				snapshot.getStreamCount(), snapshot.getSnapshotDigest(), snapshot.getCreatedBy(),
				snapshot.getCreatedAt());
	}
}
