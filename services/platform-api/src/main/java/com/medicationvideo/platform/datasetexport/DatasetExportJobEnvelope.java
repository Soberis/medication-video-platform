package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

public record DatasetExportJobEnvelope(String schemaVersion, UUID messageId, UUID exportId, String jobType, int attempt,
		String leaseToken, UUID projectId, UUID datasetVersionId, String snapshotDigest, String exportProfileKey,
		String exportProfileDigest, Instant requestedAt) {

	public static final String SCHEMA_VERSION = "dataset-export-job.v1";

	public static final String JOB_TYPE = "DATASET_EXPORT";

	static DatasetExportJobEnvelope create(DatasetExport export, int attempt, UUID messageId, String leaseToken,
			Instant requestedAt) {
		return new DatasetExportJobEnvelope(SCHEMA_VERSION, messageId, export.getDatasetExportId(), JOB_TYPE, attempt,
				leaseToken, export.getProjectId(), export.getDatasetVersionId(), export.getSnapshotDigest(),
				export.getExportProfileKey(), export.getExportProfileDigest(), requestedAt);
	}
}
