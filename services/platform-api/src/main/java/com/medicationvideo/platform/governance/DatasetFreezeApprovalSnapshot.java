package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record DatasetFreezeApprovalSnapshot(
		UUID approvalRequestId,
		UUID datasetVersionId,
		UUID validationId,
		String status,
		String requestedBy,
		Instant requestedAt,
		UUID approvalDecisionId,
		String approvedBy,
		Instant approvedAt) {

	public boolean pending() {
		return "PENDING".equals(this.status);
	}

	public boolean approved() {
		return "APPROVED".equals(this.status);
	}
}
