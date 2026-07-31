package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

record DatasetBuildSpecificationResponse(UUID datasetBuildSpecificationId, UUID datasetVersionId,
		DatasetSelectionRuleVersion selectionRuleVersion, String labelSchemaDigest, String cleaningRuleSetDigest,
		UUID datasetSplitPlanId, String exportProfileKey, String exportProfileDigest, String createdBy,
		Instant createdAt) {

	static DatasetBuildSpecificationResponse from(DatasetBuildSpecification specification) {
		return new DatasetBuildSpecificationResponse(specification.getDatasetBuildSpecificationId(),
				specification.getDatasetVersionId(), specification.getSelectionRuleVersion(),
				specification.getLabelSchemaDigest(), specification.getCleaningRuleSetDigest(),
				specification.getDatasetSplitPlanId(), specification.getExportProfileKey(),
				specification.getExportProfileDigest(), specification.getCreatedBy(), specification.getCreatedAt());
	}
}
