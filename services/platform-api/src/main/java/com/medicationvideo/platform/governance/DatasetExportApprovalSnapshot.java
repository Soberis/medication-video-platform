package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record DatasetExportApprovalSnapshot(
		UUID approvalRequestId,
		UUID datasetVersionId,
		UUID validationId,
		String status,
		String requestedBy,
		Instant requestedAt) {
}
