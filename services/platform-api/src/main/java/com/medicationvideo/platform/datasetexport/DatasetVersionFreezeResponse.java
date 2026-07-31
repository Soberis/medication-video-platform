package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

import com.medicationvideo.platform.governance.DatasetFreezeApprovalSnapshot;

record DatasetVersionFreezeResponse(UUID datasetVersionId, UUID projectId, DatasetVersionStatus status,
		UUID validationId, UUID approvalRequestId, UUID approvalDecisionId, String frozenBy, Instant frozenAt) {

	static DatasetVersionFreezeResponse from(DatasetVersion version, DatasetVersionValidation validation,
			DatasetFreezeApprovalSnapshot approval) {
		return new DatasetVersionFreezeResponse(version.getDatasetVersionId(), version.getProjectId(),
				version.getStatus(), validation.getDatasetVersionValidationId(), approval.approvalRequestId(),
				approval.approvalDecisionId(), version.getFrozenBy(), version.getFrozenAt());
	}
}
