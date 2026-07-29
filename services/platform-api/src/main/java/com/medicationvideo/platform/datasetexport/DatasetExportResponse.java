package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

import com.medicationvideo.platform.governance.DatasetExportApprovalSnapshot;

record DatasetExportResponse(
		UUID datasetExportId,
		UUID projectId,
		UUID datasetVersionId,
		DatasetExportStatus status,
		DatasetExportPurpose purpose,
		Instant expiresAt,
		UUID datasetBuildSpecificationId,
		UUID datasetBuildSnapshotId,
		String snapshotDigest,
		UUID datasetVersionValidationId,
		String exportProfileKey,
		String exportProfileDigest,
		DatasetExportApprovalResponse approval,
		String requestedBy,
		Instant requestedAt) {

	static DatasetExportResponse from(
			DatasetExport export, DatasetExportApprovalSnapshot approval) {
		if (!export.getApprovalRequestId().equals(approval.approvalRequestId())) {
			throw new IllegalStateException("The dataset export does not match its approval request.");
		}
		return new DatasetExportResponse(
				export.getDatasetExportId(),
				export.getProjectId(),
				export.getDatasetVersionId(),
				export.getStatus(),
				export.getPurpose(),
				export.getExpiresAt(),
				export.getDatasetBuildSpecificationId(),
				export.getDatasetBuildSnapshotId(),
				export.getSnapshotDigest(),
				export.getDatasetVersionValidationId(),
				export.getExportProfileKey(),
				export.getExportProfileDigest(),
				DatasetExportApprovalResponse.from(approval),
				export.getRequestedBy(),
				export.getRequestedAt());
	}
}

record DatasetExportApprovalResponse(
		UUID approvalRequestId,
		UUID validationId,
		String status,
		String requestedBy,
		Instant requestedAt) {

	static DatasetExportApprovalResponse from(DatasetExportApprovalSnapshot approval) {
		return new DatasetExportApprovalResponse(
				approval.approvalRequestId(),
				approval.validationId(),
				approval.status(),
				approval.requestedBy(),
				approval.requestedAt());
	}
}
