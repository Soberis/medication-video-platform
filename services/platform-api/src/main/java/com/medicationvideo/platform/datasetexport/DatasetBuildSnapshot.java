package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_build_snapshot")
class DatasetBuildSnapshot {

	static final String SNAPSHOT_SCHEMA_VERSION = "DATASET_RELATIONAL_SNAPSHOT_V1";
	static final String INPUT_VARIANT_POLICY = "RAW_CLIP_ASSET_V1";

	@Id
	@Column(name = "dataset_build_snapshot_id", nullable = false, updatable = false)
	private UUID datasetBuildSnapshotId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "dataset_build_specification_id", nullable = false, updatable = false)
	private UUID datasetBuildSpecificationId;

	@Column(name = "dataset_split_plan_id", nullable = false, updatable = false)
	private UUID datasetSplitPlanId;

	@Column(name = "snapshot_schema_version", nullable = false, updatable = false, length = 64)
	private String snapshotSchemaVersion;

	@Column(name = "input_variant_policy", nullable = false, updatable = false, length = 64)
	private String inputVariantPolicy;

	@Column(name = "member_count", nullable = false, updatable = false)
	private int memberCount;

	@Column(name = "stream_count", nullable = false, updatable = false)
	private int streamCount;

	@Column(name = "snapshot_digest", nullable = false, updatable = false, length = 64)
	private String snapshotDigest;

	@Column(name = "created_by", nullable = false, updatable = false, length = 128)
	private String createdBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected DatasetBuildSnapshot() {
	}

	private DatasetBuildSnapshot(DatasetVersion version, DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan, int memberCount, int streamCount, String snapshotDigest, String createdBy,
			String idempotencyKey, String requestFingerprint, Instant createdAt) {
		this.datasetBuildSnapshotId = UUID.randomUUID();
		this.datasetVersionId = version.getDatasetVersionId();
		this.projectId = version.getProjectId();
		this.datasetBuildSpecificationId = specification.getDatasetBuildSpecificationId();
		this.datasetSplitPlanId = splitPlan.getDatasetSplitPlanId();
		this.snapshotSchemaVersion = SNAPSHOT_SCHEMA_VERSION;
		this.inputVariantPolicy = INPUT_VARIANT_POLICY;
		this.memberCount = memberCount;
		this.streamCount = streamCount;
		this.snapshotDigest = snapshotDigest;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static DatasetBuildSnapshot create(DatasetVersion version, DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan, int memberCount, int streamCount, String snapshotDigest, String createdBy,
			String idempotencyKey, String requestFingerprint, Instant createdAt) {
		return new DatasetBuildSnapshot(version, specification, splitPlan, memberCount, streamCount, snapshotDigest,
				createdBy, idempotencyKey, requestFingerprint, createdAt);
	}

	boolean matchesCreateRequest(String fingerprint) {
		return this.requestFingerprint.equals(fingerprint);
	}

	UUID getDatasetBuildSnapshotId() {
		return this.datasetBuildSnapshotId;
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getDatasetBuildSpecificationId() {
		return this.datasetBuildSpecificationId;
	}

	UUID getDatasetSplitPlanId() {
		return this.datasetSplitPlanId;
	}

	String getSnapshotSchemaVersion() {
		return this.snapshotSchemaVersion;
	}

	String getInputVariantPolicy() {
		return this.inputVariantPolicy;
	}

	int getMemberCount() {
		return this.memberCount;
	}

	int getStreamCount() {
		return this.streamCount;
	}

	String getSnapshotDigest() {
		return this.snapshotDigest;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

}
