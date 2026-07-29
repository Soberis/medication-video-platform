package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_split_plan")
class DatasetSplitPlan {

	@Id
	@Column(name = "dataset_split_plan_id", nullable = false, updatable = false)
	private UUID datasetSplitPlanId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Enumerated(EnumType.STRING)
	@Column(name = "policy_version", nullable = false, updatable = false, length = 64)
	private DatasetSplitPolicy policyVersion;

	@Column(name = "split_seed", nullable = false, updatable = false)
	private int splitSeed;

	@Column(name = "holdout_count", nullable = false, updatable = false)
	private int holdoutCount;

	@Column(name = "created_by", nullable = false, updatable = false, length = 128)
	private String createdBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected DatasetSplitPlan() {
	}

	private DatasetSplitPlan(
			DatasetVersion version,
			int splitSeed,
			int holdoutCount,
			String createdBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		this.datasetSplitPlanId = UUID.randomUUID();
		this.datasetVersionId = version.getDatasetVersionId();
		this.projectId = version.getProjectId();
		this.policyVersion = DatasetSplitPolicy.CAPTURE_SESSION_HASH_RANK_V1;
		this.splitSeed = splitSeed;
		this.holdoutCount = holdoutCount;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static DatasetSplitPlan create(
			DatasetVersion version,
			int splitSeed,
			int holdoutCount,
			String createdBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		return new DatasetSplitPlan(
				version, splitSeed, holdoutCount, createdBy, idempotencyKey, requestFingerprint, createdAt);
	}

	boolean matchesCreateRequest(String fingerprint) {
		return this.requestFingerprint.equals(fingerprint);
	}

	UUID getDatasetSplitPlanId() {
		return this.datasetSplitPlanId;
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	DatasetSplitPolicy getPolicyVersion() {
		return this.policyVersion;
	}

	int getSplitSeed() {
		return this.splitSeed;
	}

	int getHoldoutCount() {
		return this.holdoutCount;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}
}

enum DatasetSplitPolicy {
	CAPTURE_SESSION_HASH_RANK_V1
}
