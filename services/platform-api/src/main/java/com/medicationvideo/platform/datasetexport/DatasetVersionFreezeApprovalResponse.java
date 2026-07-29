package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

import com.medicationvideo.platform.governance.DatasetFreezeApprovalSnapshot;

record DatasetVersionFreezeApprovalResponse(
		UUID approvalRequestId,
		UUID validationId,
		String status,
		String requestedBy,
		Instant requestedAt,
		UUID approvalDecisionId,
		String approvedBy,
		Instant approvedAt) {

	static DatasetVersionFreezeApprovalResponse from(DatasetFreezeApprovalSnapshot approval) {
		return new DatasetVersionFreezeApprovalResponse(
				approval.approvalRequestId(),
				approval.validationId(),
				approval.status(),
				approval.requestedBy(),
				approval.requestedAt(),
				approval.approvalDecisionId(),
				approval.approvedBy(),
				approval.approvedAt());
	}
}
