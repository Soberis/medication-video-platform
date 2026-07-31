package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record DatasetExportApprovalReadinessResponse(UUID datasetExportId, UUID datasetVersionId, UUID approvalRequestId,
		boolean readyForApproval, List<String> blockingReasons, Instant checkedAt) {

	DatasetExportApprovalReadinessResponse {
		blockingReasons = List.copyOf(blockingReasons);
	}
}
