package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record DatasetVersionValidationResponse(
		UUID datasetVersionValidationId,
		UUID datasetVersionId,
		UUID datasetBuildSnapshotId,
		String validationProfileVersion,
		DatasetVersionValidationOutcome outcome,
		List<String> reasonCodes,
		List<String> executedChecks,
		String validatedSnapshotDigest,
		String validatedBy,
		Instant validatedAt) {

	DatasetVersionValidationResponse {
		reasonCodes = List.copyOf(reasonCodes);
		executedChecks = List.copyOf(executedChecks);
	}

	static DatasetVersionValidationResponse from(DatasetVersionValidation validation) {
		return new DatasetVersionValidationResponse(
				validation.getDatasetVersionValidationId(),
				validation.getDatasetVersionId(),
				validation.getDatasetBuildSnapshotId(),
				validation.getValidationProfileVersion(),
				validation.getOutcome(),
				validation.getReasonCodes(),
				validation.getExecutedChecks(),
				validation.getValidatedSnapshotDigest(),
				validation.getValidatedBy(),
				validation.getValidatedAt());
	}
}
