package com.medicationvideo.platform.datasetexport;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DatasetRelationalSnapshotChecks {

	static final String BUILD_CONFIGURATION_MISMATCH = "BUILD_CONFIGURATION_MISMATCH";
	static final String MEMBER_EVIDENCE_MISSING = "MEMBER_EVIDENCE_MISSING";
	static final String SPLIT_ASSIGNMENT_INCOMPLETE = "SPLIT_ASSIGNMENT_INCOMPLETE";
	static final String STREAM_EVIDENCE_INCOMPLETE = "STREAM_EVIDENCE_INCOMPLETE";
	static final String SPLIT_LEAKAGE_DETECTED = "SPLIT_LEAKAGE_DETECTED";

	private DatasetRelationalSnapshotChecks() {
	}

	static List<String> structuralIssues(
			DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan,
			List<DatasetMemberSession> members,
			List<DatasetMemberStream> streams,
			List<DatasetSplitAssignment> assignments) {
		var issues = new LinkedHashSet<String>();
		if (!specification.getDatasetSplitPlanId().equals(splitPlan.getDatasetSplitPlanId())) {
			issues.add(BUILD_CONFIGURATION_MISMATCH);
		}
		if (members.isEmpty()) {
			issues.add(MEMBER_EVIDENCE_MISSING);
		}

		var membersById = new HashMap<UUID, DatasetMemberSession>();
		for (var member : members) {
			if (membersById.put(member.getDatasetMemberSessionId(), member) != null) {
				issues.add(MEMBER_EVIDENCE_MISSING);
			}
		}
		var streamsByMember = new HashMap<UUID, Integer>();
		for (var stream : streams) {
			if (!membersById.containsKey(stream.getDatasetMemberSessionId())) {
				issues.add(STREAM_EVIDENCE_INCOMPLETE);
			}
			streamsByMember.merge(stream.getDatasetMemberSessionId(), 1, Integer::sum);
		}
		var assignmentsByMember = new HashMap<UUID, DatasetSplitAssignment>();
		for (var assignment : assignments) {
			if (assignmentsByMember.put(assignment.getDatasetMemberSessionId(), assignment) != null) {
				issues.add(SPLIT_ASSIGNMENT_INCOMPLETE);
			}
		}
		if (assignmentsByMember.size() != members.size()) {
			issues.add(SPLIT_ASSIGNMENT_INCOMPLETE);
		}

		Map<UUID, DatasetSplit> splitByLeakageGroup = new HashMap<>();
		for (var member : members) {
			var memberStreamCount = streamsByMember.getOrDefault(member.getDatasetMemberSessionId(), 0);
			if (member.getExpectedCameraCount() != member.getReceivedCameraCount()
					|| memberStreamCount != member.getExpectedCameraCount()) {
				issues.add(STREAM_EVIDENCE_INCOMPLETE);
			}
			var assignment = assignmentsByMember.get(member.getDatasetMemberSessionId());
			if (assignment == null
					|| !assignment.getCaptureSessionId().equals(member.getCaptureSessionId())
					|| assignment.getCandidateIndex() != member.getCandidateIndex()
					|| assignment.getLeakageGroupType() != DatasetLeakageGroupType.CAPTURE_SESSION
					|| !assignment.getLeakageGroupId().equals(member.getCaptureSessionId())) {
				issues.add(SPLIT_ASSIGNMENT_INCOMPLETE);
				continue;
			}
			var previousSplit = splitByLeakageGroup.putIfAbsent(
					assignment.getLeakageGroupId(), assignment.getSplit());
			if (previousSplit != null && previousSplit != assignment.getSplit()) {
				issues.add(SPLIT_LEAKAGE_DETECTED);
			}
		}
		if (streams.isEmpty()) {
			issues.add(STREAM_EVIDENCE_INCOMPLETE);
		}
		if (streams.stream().anyMatch(stream ->
				!specification.getLabelSchemaDigest().equals(stream.getLabelSchemaDigest())
						|| !specification.getCleaningRuleSetDigest().equals(
								stream.getCleaningRuleSetDigest()))) {
			issues.add(BUILD_CONFIGURATION_MISMATCH);
		}
		return List.copyOf(issues);
	}
}
