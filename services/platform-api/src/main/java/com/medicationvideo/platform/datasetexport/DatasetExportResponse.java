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
		Instant requestedAt,
		String approvedBy,
		Instant approvedAt,
		Instant startedAt,
		Instant completedAt,
		DatasetExportAttemptResponse attempt,
		ExportArtifactResponse artifact) {

	static DatasetExportResponse from(
			DatasetExport export, DatasetExportApprovalSnapshot approval) {
		return from(export, approval, null, null);
	}

	static DatasetExportResponse from(
			DatasetExport export,
			DatasetExportApprovalSnapshot approval,
			DatasetExportAttempt attempt,
			ExportArtifact artifact) {
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
				export.getRequestedAt(),
				export.getApprovedBy(),
				export.getApprovedAt(),
				export.getStartedAt(),
				export.getCompletedAt(),
				attempt == null ? null : DatasetExportAttemptResponse.from(attempt),
				artifact == null ? null : ExportArtifactResponse.from(artifact));
	}
}

record DatasetExportApprovalResponse(
		UUID approvalRequestId,
		UUID validationId,
		String status,
		String requestedBy,
		Instant requestedAt,
		UUID approvalDecisionId,
		String decidedBy,
		Instant decidedAt) {

	static DatasetExportApprovalResponse from(DatasetExportApprovalSnapshot approval) {
		return new DatasetExportApprovalResponse(
				approval.approvalRequestId(),
				approval.validationId(),
				approval.status(),
				approval.requestedBy(),
				approval.requestedAt(),
				approval.approvalDecisionId(),
				approval.decidedBy(),
				approval.decidedAt());
	}
}

record DatasetExportAttemptResponse(
		UUID datasetExportAttemptId,
		int attempt,
		DatasetExportAttemptStatus status,
		Instant startedAt,
		Instant completedAt) {

	static DatasetExportAttemptResponse from(DatasetExportAttempt attempt) {
		return new DatasetExportAttemptResponse(
				attempt.getDatasetExportAttemptId(),
				attempt.getAttempt(),
				attempt.getStatus(),
				attempt.getStartedAt(),
				attempt.getCompletedAt());
	}
}

record ExportArtifactResponse(
		UUID exportArtifactId,
		String fileName,
		String mediaType,
		long sizeBytes,
		String sha256,
		Instant createdAt) {

	static ExportArtifactResponse from(ExportArtifact artifact) {
		return new ExportArtifactResponse(
				artifact.getExportArtifactId(),
				artifact.getFileName(),
				artifact.getMediaType(),
				artifact.getSizeBytes(),
				artifact.getSha256(),
				artifact.getCreatedAt());
	}
}
