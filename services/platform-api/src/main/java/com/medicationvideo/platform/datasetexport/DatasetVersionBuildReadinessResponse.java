package com.medicationvideo.platform.datasetexport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

record DatasetVersionBuildReadinessResponse(boolean readyForBuild, List<DatasetVersionBuildBlocker> blockingReasons,
		boolean immutableEvidenceStillCurrent, String uniformLabelSchemaDigest, String uniformCleaningRuleSetDigest,
		UUID datasetSplitPlanId, DatasetBuildSpecificationResponse buildSpecification) {

	DatasetVersionBuildReadinessResponse {
		blockingReasons = List.copyOf(blockingReasons);
	}

	static DatasetVersionBuildReadinessResponse from(List<DatasetVersionCurrentAdmissionMemberResponse> currentMembers,
			List<DatasetMemberStream> snapshotStreams, DatasetSplitPlan splitPlan,
			DatasetBuildSpecification specification) {
		var allMembersEligible = currentMembers.stream().allMatch(member -> member.currentAdmission().eligible());
		var evidenceStillCurrent = currentMembers.stream().allMatch(member -> member.evidenceChanges().isEmpty());
		var buildEvidence = DatasetBuildEvidence.from(snapshotStreams);
		var labelSchemaDigest = buildEvidence.uniformLabelSchemaDigest();
		var cleaningRuleSetDigest = buildEvidence.uniformCleaningRuleSetDigest();
		var blockers = new ArrayList<DatasetVersionBuildBlocker>();

		if (specification == null) {
			blockers.add(DatasetVersionBuildBlocker.SELECTION_RULE_NOT_FIXED);
		}
		if (labelSchemaDigest == null) {
			blockers.add(DatasetVersionBuildBlocker.LABEL_SCHEMA_NOT_UNIFORM);
		}
		else if (specification == null || !labelSchemaDigest.equals(specification.getLabelSchemaDigest())) {
			blockers.add(DatasetVersionBuildBlocker.LABEL_SCHEMA_NOT_FIXED);
		}
		if (cleaningRuleSetDigest == null) {
			blockers.add(DatasetVersionBuildBlocker.CLEANING_RULE_SET_NOT_UNIFORM);
		}
		else if (specification == null || !cleaningRuleSetDigest.equals(specification.getCleaningRuleSetDigest())) {
			blockers.add(DatasetVersionBuildBlocker.CLEANING_RULE_SET_NOT_FIXED);
		}
		if (splitPlan == null) {
			blockers.add(DatasetVersionBuildBlocker.SPLIT_PLAN_NOT_FIXED);
		}
		if (specification == null) {
			blockers.add(DatasetVersionBuildBlocker.EXPORT_PROFILE_NOT_FIXED);
		}
		if (!allMembersEligible) {
			blockers.add(DatasetVersionBuildBlocker.CURRENT_ADMISSION_NOT_ELIGIBLE);
		}
		if (!evidenceStillCurrent) {
			blockers.add(DatasetVersionBuildBlocker.SNAPSHOT_EVIDENCE_CHANGED);
		}

		return new DatasetVersionBuildReadinessResponse(blockers.isEmpty(), blockers, evidenceStillCurrent,
				labelSchemaDigest, cleaningRuleSetDigest, splitPlan == null ? null : splitPlan.getDatasetSplitPlanId(),
				specification == null ? null : DatasetBuildSpecificationResponse.from(specification));
	}

}

enum DatasetVersionBuildBlocker {

	SELECTION_RULE_NOT_FIXED, LABEL_SCHEMA_NOT_FIXED, LABEL_SCHEMA_NOT_UNIFORM, CLEANING_RULE_SET_NOT_FIXED,
	CLEANING_RULE_SET_NOT_UNIFORM, SPLIT_PLAN_NOT_FIXED, EXPORT_PROFILE_NOT_FIXED, CURRENT_ADMISSION_NOT_ELIGIBLE,
	SNAPSHOT_EVIDENCE_CHANGED

}
