package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

final class DatasetBuildSnapshotDigest {

	private DatasetBuildSnapshotDigest() {
	}

	static String compute(
			DatasetVersion version,
			DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan,
			List<DatasetMemberSession> members,
			List<DatasetMemberStream> streams,
			List<DatasetSplitAssignment> assignments) {
		var orderedMembers = members.stream()
				.sorted(Comparator.comparingInt(DatasetMemberSession::getCandidateIndex))
				.toList();
		var streamsByMember = streams.stream()
				.sorted(Comparator.comparing(DatasetMemberStream::getDatasetMemberSessionId)
						.thenComparingInt(DatasetMemberStream::getCameraIndex)
						.thenComparing(DatasetMemberStream::getCameraStreamId))
				.collect(Collectors.groupingBy(
						DatasetMemberStream::getDatasetMemberSessionId,
						Collectors.toList()));
		Map<UUID, DatasetSplitAssignment> assignmentsByMember = assignments.stream()
				.collect(Collectors.toUnmodifiableMap(
						DatasetSplitAssignment::getDatasetMemberSessionId,
						Function.identity()));

		var canonical = new StringBuilder();
		append(canonical, "snapshot_schema_version", DatasetBuildSnapshot.SNAPSHOT_SCHEMA_VERSION);
		append(canonical, "input_variant_policy", DatasetBuildSnapshot.INPUT_VARIANT_POLICY);
		append(canonical, "project_id", version.getProjectId());
		append(canonical, "dataset_version_id", version.getDatasetVersionId());
		append(canonical, "dataset_build_specification_id", specification.getDatasetBuildSpecificationId());
		append(canonical, "selection_rule_version", specification.getSelectionRuleVersion());
		append(canonical, "label_schema_digest", specification.getLabelSchemaDigest());
		append(canonical, "cleaning_rule_set_digest", specification.getCleaningRuleSetDigest());
		append(canonical, "export_profile_key", specification.getExportProfileKey());
		append(canonical, "export_profile_digest", specification.getExportProfileDigest());
		append(canonical, "dataset_split_plan_id", splitPlan.getDatasetSplitPlanId());
		append(canonical, "split_policy_version", splitPlan.getPolicyVersion());
		append(canonical, "split_seed", splitPlan.getSplitSeed());
		append(canonical, "holdout_count", splitPlan.getHoldoutCount());
		append(canonical, "member_count", orderedMembers.size());
		append(canonical, "stream_count", streams.size());

		for (var member : orderedMembers) {
			append(canonical, "member_id", member.getDatasetMemberSessionId());
			append(canonical, "capture_session_id", member.getCaptureSessionId());
			append(canonical, "candidate_index", member.getCandidateIndex());
			append(canonical, "expected_camera_count", member.getExpectedCameraCount());
			append(canonical, "received_camera_count", member.getReceivedCameraCount());
			append(canonical, "assembly_status", member.getAssemblyStatus());
			append(canonical, "quality_status", member.getQualityStatus());

			var assignment = requireAssignment(assignmentsByMember, member.getDatasetMemberSessionId());
			append(canonical, "leakage_group_type", assignment.getLeakageGroupType());
			append(canonical, "leakage_group_id", assignment.getLeakageGroupId());
			append(canonical, "split", assignment.getSplit());
			append(canonical, "assignment_rank", assignment.getAssignmentRank());

			var memberStreams = streamsByMember.getOrDefault(member.getDatasetMemberSessionId(), List.of());
			append(canonical, "member_stream_count", memberStreams.size());
			for (var stream : memberStreams) {
				append(canonical, "camera_stream_id", stream.getCameraStreamId());
				append(canonical, "raw_clip_asset_id", stream.getClipAssetId());
				append(canonical, "camera_index", stream.getCameraIndex());
				append(canonical, "raw_asset_sha256", stream.getAssetSha256());
				append(canonical, "quality_review_decision", stream.getQualityReviewDecision());
				append(canonical, "quality_review_version", stream.getQualityReviewVersion());
				append(canonical, "annotation_revision_id", stream.getAnnotationRevisionId());
				append(canonical, "annotation_document_sha256", stream.getAnnotationDocumentSha256());
				append(canonical, "stream_label_schema_digest", stream.getLabelSchemaDigest());
				append(canonical, "cleaning_result_id", stream.getCleaningResultId());
				append(canonical, "cleaning_run_id", stream.getCleaningRunId());
				append(canonical, "stream_cleaning_rule_set_digest", stream.getCleaningRuleSetDigest());
				append(canonical, "post_cleaning_outcome", stream.getPostCleaningOutcome());
			}
		}
		return sha256(canonical.toString());
	}

	private static DatasetSplitAssignment requireAssignment(
			Map<UUID, DatasetSplitAssignment> assignmentsByMember, UUID memberId) {
		var assignment = assignmentsByMember.get(memberId);
		if (assignment == null) {
			throw new IllegalArgumentException("Every dataset member must have one split assignment.");
		}
		return assignment;
	}

	private static void append(StringBuilder canonical, String name, Object value) {
		var text = String.valueOf(value);
		canonical.append(name.length())
				.append(':')
				.append(name)
				.append('=')
				.append(text.length())
				.append(':')
				.append(text)
				.append('\n');
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
