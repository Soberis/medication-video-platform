package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "dataset_version")
class DatasetVersion {

	@Id
	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "display_name", nullable = false, updatable = false, length = 128)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "dataset_version_status", nullable = false, length = 16)
	private DatasetVersionStatus status;

	@Column(name = "created_by", nullable = false, updatable = false, length = 128)
	private String createdBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "build_started_by", length = 128)
	private String buildStartedBy;

	@Column(name = "build_start_idempotency_key", length = 128)
	private String buildStartIdempotencyKey;

	@Column(name = "build_start_request_fingerprint", length = 64)
	private String buildStartRequestFingerprint;

	@Column(name = "build_started_at")
	private Instant buildStartedAt;

	@Column(name = "build_snapshot_id")
	private UUID buildSnapshotId;

	@Column(name = "freeze_approval_request_id")
	private UUID freezeApprovalRequestId;

	@Column(name = "freeze_approval_decision_id")
	private UUID freezeApprovalDecisionId;

	@Column(name = "frozen_by", length = 128)
	private String frozenBy;

	@Column(name = "freeze_idempotency_key", length = 128)
	private String freezeIdempotencyKey;

	@Column(name = "freeze_request_fingerprint", length = 64)
	private String freezeRequestFingerprint;

	@Column(name = "frozen_at")
	private Instant frozenAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long version;

	protected DatasetVersion() {
	}

	private DatasetVersion(UUID projectId, String displayName, String createdBy, String idempotencyKey,
			String requestFingerprint, Instant createdAt) {
		this.datasetVersionId = UUID.randomUUID();
		this.projectId = projectId;
		this.displayName = displayName;
		this.status = DatasetVersionStatus.DRAFT;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static DatasetVersion create(UUID projectId, String displayName, String createdBy, String idempotencyKey,
			String requestFingerprint, Instant createdAt) {
		return new DatasetVersion(projectId, displayName, createdBy, idempotencyKey, requestFingerprint, createdAt);
	}

	boolean matchesCreateRequest(String requestFingerprint) {
		return this.requestFingerprint.equals(requestFingerprint);
	}

	boolean matchesBuildStartRequest(String actorId, String idempotencyKey, String requestFingerprint) {
		return Objects.equals(this.buildStartedBy, actorId)
				&& Objects.equals(this.buildStartIdempotencyKey, idempotencyKey)
				&& Objects.equals(this.buildStartRequestFingerprint, requestFingerprint);
	}

	boolean matchesFreezeRequest(String actorId, String idempotencyKey, String requestFingerprint) {
		return Objects.equals(this.frozenBy, actorId) && Objects.equals(this.freezeIdempotencyKey, idempotencyKey)
				&& Objects.equals(this.freezeRequestFingerprint, requestFingerprint);
	}

	void startBuild(String actorId, String idempotencyKey, String requestFingerprint, Instant startedAt) {
		if (this.status != DatasetVersionStatus.DRAFT || this.buildStartedAt != null || this.buildSnapshotId != null) {
			throw new IllegalStateException("Only a dataset draft can start building.");
		}
		this.status = DatasetVersionStatus.BUILDING;
		this.buildStartedBy = actorId;
		this.buildStartIdempotencyKey = idempotencyKey;
		this.buildStartRequestFingerprint = requestFingerprint;
		this.buildStartedAt = startedAt;
	}

	void beginValidation(UUID buildSnapshotId) {
		if (this.status != DatasetVersionStatus.BUILDING || this.buildSnapshotId != null) {
			throw new IllegalStateException("Only a building dataset version can begin validation.");
		}
		this.status = DatasetVersionStatus.VALIDATING;
		this.buildSnapshotId = Objects.requireNonNull(buildSnapshotId, "buildSnapshotId");
	}

	void freeze(UUID approvalRequestId, UUID approvalDecisionId, String actorId, String idempotencyKey,
			String requestFingerprint, Instant frozenAt) {
		if (this.status != DatasetVersionStatus.VALIDATING || this.buildSnapshotId == null
				|| this.freezeApprovalRequestId != null || this.freezeApprovalDecisionId != null
				|| this.frozenAt != null) {
			throw new IllegalStateException("Only a validated dataset version can be frozen.");
		}
		this.status = DatasetVersionStatus.FROZEN;
		this.freezeApprovalRequestId = Objects.requireNonNull(approvalRequestId, "approvalRequestId");
		this.freezeApprovalDecisionId = Objects.requireNonNull(approvalDecisionId, "approvalDecisionId");
		this.frozenBy = actorId;
		this.freezeIdempotencyKey = idempotencyKey;
		this.freezeRequestFingerprint = requestFingerprint;
		this.frozenAt = frozenAt;
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getDisplayName() {
		return this.displayName;
	}

	DatasetVersionStatus getStatus() {
		return this.status;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	String getBuildStartedBy() {
		return this.buildStartedBy;
	}

	Instant getBuildStartedAt() {
		return this.buildStartedAt;
	}

	UUID getBuildSnapshotId() {
		return this.buildSnapshotId;
	}

	UUID getFreezeApprovalDecisionId() {
		return this.freezeApprovalDecisionId;
	}

	String getFrozenBy() {
		return this.frozenBy;
	}

	Instant getFrozenAt() {
		return this.frozenAt;
	}

	long getVersion() {
		return this.version;
	}

}

enum DatasetVersionStatus {

	DRAFT, BUILDING, VALIDATING, FROZEN

}
