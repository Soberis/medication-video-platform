package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

record DatasetSplitPlanResponse(
		UUID datasetSplitPlanId,
		UUID datasetVersionId,
		DatasetSplitPolicy policyVersion,
		int splitSeed,
		int pilotCount,
		int holdoutCount,
		String createdBy,
		Instant createdAt,
		List<DatasetSplitAssignmentResponse> assignments) {

	DatasetSplitPlanResponse {
		assignments = List.copyOf(assignments);
	}

	static DatasetSplitPlanResponse from(
			DatasetSplitPlan plan, List<DatasetSplitAssignment> assignments) {
		var orderedAssignments = assignments.stream()
				.sorted(Comparator.comparingInt(DatasetSplitAssignment::getCandidateIndex))
				.toList();
		return new DatasetSplitPlanResponse(
				plan.getDatasetSplitPlanId(),
				plan.getDatasetVersionId(),
				plan.getPolicyVersion(),
				plan.getSplitSeed(),
				orderedAssignments.size() - plan.getHoldoutCount(),
				plan.getHoldoutCount(),
				plan.getCreatedBy(),
				plan.getCreatedAt(),
				orderedAssignments.stream().map(DatasetSplitAssignmentResponse::from).toList());
	}
}

record DatasetSplitAssignmentResponse(
		UUID captureSessionId,
		int candidateIndex,
		DatasetLeakageGroupType leakageGroupType,
		UUID leakageGroupId,
		DatasetSplit split,
		int assignmentRank) {

	static DatasetSplitAssignmentResponse from(DatasetSplitAssignment assignment) {
		return new DatasetSplitAssignmentResponse(
				assignment.getCaptureSessionId(),
				assignment.getCandidateIndex(),
				assignment.getLeakageGroupType(),
				assignment.getLeakageGroupId(),
				assignment.getSplit(),
				assignment.getAssignmentRank());
	}
}
