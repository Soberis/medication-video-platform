package com.medicationvideo.platform.datasetexport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

record DatasetVersionBuildReadinessResponse(
		boolean readyForBuild,
		List<DatasetVersionBuildBlocker> blockingReasons,
		boolean immutableEvidenceStillCurrent,
		String uniformLabelSchemaDigest,
		String uniformCleaningRuleSetDigest,
		UUID datasetSplitPlanId) {

	DatasetVersionBuildReadinessResponse {
		blockingReasons = List.copyOf(blockingReasons);
	}

	static DatasetVersionBuildReadinessResponse from(
			List<DatasetVersionCurrentAdmissionMemberResponse> currentMembers,
			List<DatasetMemberStream> snapshotStreams,
			DatasetSplitPlan splitPlan) {
		var allMembersEligible = currentMembers.stream()
				.allMatch(member -> member.currentAdmission().eligible());
		var evidenceStillCurrent = currentMembers.stream()
				.allMatch(member -> member.evidenceChanges().isEmpty());
		var labelSchemaDigest = onlyDistinct(snapshotStreams, DatasetMemberStream::getLabelSchemaDigest);
		var cleaningRuleSetDigest = onlyDistinct(snapshotStreams, DatasetMemberStream::getCleaningRuleSetDigest);
		var blockers = new ArrayList<DatasetVersionBuildBlocker>();

		blockers.add(DatasetVersionBuildBlocker.SELECTION_RULE_NOT_FIXED);
		blockers.add(labelSchemaDigest == null
				? DatasetVersionBuildBlocker.LABEL_SCHEMA_NOT_UNIFORM
				: DatasetVersionBuildBlocker.LABEL_SCHEMA_NOT_FIXED);
		blockers.add(cleaningRuleSetDigest == null
				? DatasetVersionBuildBlocker.CLEANING_RULE_SET_NOT_UNIFORM
				: DatasetVersionBuildBlocker.CLEANING_RULE_SET_NOT_FIXED);
		if (splitPlan == null) {
			blockers.add(DatasetVersionBuildBlocker.SPLIT_PLAN_NOT_FIXED);
		}
		blockers.add(DatasetVersionBuildBlocker.EXPORT_PROFILE_NOT_FIXED);
		if (!allMembersEligible) {
			blockers.add(DatasetVersionBuildBlocker.CURRENT_ADMISSION_NOT_ELIGIBLE);
		}
		if (!evidenceStillCurrent) {
			blockers.add(DatasetVersionBuildBlocker.SNAPSHOT_EVIDENCE_CHANGED);
		}

		return new DatasetVersionBuildReadinessResponse(
				blockers.isEmpty(),
				blockers,
				evidenceStillCurrent,
				labelSchemaDigest,
				cleaningRuleSetDigest,
				splitPlan == null ? null : splitPlan.getDatasetSplitPlanId());
	}

	private static String onlyDistinct(
			List<DatasetMemberStream> streams, Function<DatasetMemberStream, String> value) {
		var values = new LinkedHashSet<String>();
		for (var stream : streams) {
			values.add(value.apply(stream));
			if (values.size() > 1) {
				return null;
			}
		}
		return values.size() == 1 ? values.getFirst() : null;
	}
}

enum DatasetVersionBuildBlocker {
	SELECTION_RULE_NOT_FIXED,
	LABEL_SCHEMA_NOT_FIXED,
	LABEL_SCHEMA_NOT_UNIFORM,
	CLEANING_RULE_SET_NOT_FIXED,
	CLEANING_RULE_SET_NOT_UNIFORM,
	SPLIT_PLAN_NOT_FIXED,
	EXPORT_PROFILE_NOT_FIXED,
	CURRENT_ADMISSION_NOT_ELIGIBLE,
	SNAPSHOT_EVIDENCE_CHANGED
}
