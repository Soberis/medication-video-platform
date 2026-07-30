package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record DatasetExportApprovalSnapshot(
		UUID approvalRequestId,
		UUID datasetVersionId,
		UUID validationId,
		String status,
		String requestedBy,
		Instant requestedAt,
		UUID approvalDecisionId,
		String decidedBy,
		Instant decidedAt) {

	public DatasetExportApprovalSnapshot(
			UUID approvalRequestId,
			UUID datasetVersionId,
			UUID validationId,
			String status,
			String requestedBy,
			Instant requestedAt) {
		this(
				approvalRequestId,
				datasetVersionId,
				validationId,
				status,
				requestedBy,
				requestedAt,
				null,
				null,
				null);
	}

	public boolean pending() {
		return "PENDING".equals(this.status);
	}

	public boolean approved() {
		return "APPROVED".equals(this.status);
	}
}
