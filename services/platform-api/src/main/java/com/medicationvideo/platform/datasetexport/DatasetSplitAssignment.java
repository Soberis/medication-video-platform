package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_split_assignment")
class DatasetSplitAssignment {

	@Id
	@Column(name = "dataset_split_assignment_id", nullable = false, updatable = false)
	private UUID datasetSplitAssignmentId;

	@Column(name = "dataset_split_plan_id", nullable = false, updatable = false)
	private UUID datasetSplitPlanId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "dataset_member_session_id", nullable = false, updatable = false)
	private UUID datasetMemberSessionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "candidate_index", nullable = false, updatable = false)
	private int candidateIndex;

	@Enumerated(EnumType.STRING)
	@Column(name = "leakage_group_type", nullable = false, updatable = false, length = 32)
	private DatasetLeakageGroupType leakageGroupType;

	@Column(name = "leakage_group_id", nullable = false, updatable = false)
	private UUID leakageGroupId;

	@Enumerated(EnumType.STRING)
	@Column(name = "split", nullable = false, updatable = false, length = 16)
	private DatasetSplit split;

	@Column(name = "assignment_rank", nullable = false, updatable = false)
	private int assignmentRank;

	protected DatasetSplitAssignment() {
	}

	private DatasetSplitAssignment(
			DatasetSplitPlan plan, DatasetMemberSession member, DatasetSplit split, int assignmentRank) {
		this.datasetSplitAssignmentId = UUID.randomUUID();
		this.datasetSplitPlanId = plan.getDatasetSplitPlanId();
		this.datasetVersionId = plan.getDatasetVersionId();
		this.datasetMemberSessionId = member.getDatasetMemberSessionId();
		this.projectId = plan.getProjectId();
		this.captureSessionId = member.getCaptureSessionId();
		this.candidateIndex = member.getCandidateIndex();
		this.leakageGroupType = DatasetLeakageGroupType.CAPTURE_SESSION;
		this.leakageGroupId = member.getCaptureSessionId();
		this.split = split;
		this.assignmentRank = assignmentRank;
	}

	static DatasetSplitAssignment assign(
			DatasetSplitPlan plan, DatasetMemberSession member, DatasetSplit split, int assignmentRank) {
		return new DatasetSplitAssignment(plan, member, split, assignmentRank);
	}

	UUID getDatasetMemberSessionId() {
		return this.datasetMemberSessionId;
	}

	UUID getCaptureSessionId() {
		return this.captureSessionId;
	}

	int getCandidateIndex() {
		return this.candidateIndex;
	}

	DatasetLeakageGroupType getLeakageGroupType() {
		return this.leakageGroupType;
	}

	UUID getLeakageGroupId() {
		return this.leakageGroupId;
	}

	DatasetSplit getSplit() {
		return this.split;
	}

	int getAssignmentRank() {
		return this.assignmentRank;
	}
}

enum DatasetLeakageGroupType {
	CAPTURE_SESSION
}

enum DatasetSplit {
	PILOT,
	HOLDOUT
}
