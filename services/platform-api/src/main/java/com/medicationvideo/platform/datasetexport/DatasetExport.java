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
@Table(name = "dataset_export")
class DatasetExport {

	@Id
	@Column(name = "dataset_export_id", nullable = false, updatable = false)
	private UUID datasetExportId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "dataset_build_specification_id", nullable = false, updatable = false)
	private UUID datasetBuildSpecificationId;

	@Column(name = "dataset_build_snapshot_id", nullable = false, updatable = false)
	private UUID datasetBuildSnapshotId;

	@Column(name = "dataset_version_validation_id", nullable = false, updatable = false)
	private UUID datasetVersionValidationId;

	@Column(name = "approval_request_id", nullable = false, updatable = false)
	private UUID approvalRequestId;

	@Column(name = "export_profile_key", nullable = false, updatable = false, length = 64)
	private String exportProfileKey;

	@Column(name = "export_profile_digest", nullable = false, updatable = false, length = 64)
	private String exportProfileDigest;

	@Column(name = "snapshot_digest", nullable = false, updatable = false, length = 64)
	private String snapshotDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, updatable = false, length = 32)
	private DatasetExportPurpose purpose;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private DatasetExportStatus status;

	@Column(name = "requested_by", nullable = false, updatable = false, length = 128)
	private String requestedBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long version;

	protected DatasetExport() {
	}

	private DatasetExport(
			DatasetVersion datasetVersion,
			DatasetBuildSpecification specification,
			DatasetBuildSnapshot snapshot,
			DatasetVersionValidation validation,
			UUID approvalRequestId,
			DatasetExportPurpose purpose,
			Instant expiresAt,
			String requestedBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant requestedAt) {
		this.datasetExportId = UUID.randomUUID();
		this.projectId = datasetVersion.getProjectId();
		this.datasetVersionId = datasetVersion.getDatasetVersionId();
		this.datasetBuildSpecificationId = specification.getDatasetBuildSpecificationId();
		this.datasetBuildSnapshotId = snapshot.getDatasetBuildSnapshotId();
		this.datasetVersionValidationId = validation.getDatasetVersionValidationId();
		this.approvalRequestId = Objects.requireNonNull(approvalRequestId, "approvalRequestId");
		this.exportProfileKey = specification.getExportProfileKey();
		this.exportProfileDigest = specification.getExportProfileDigest();
		this.snapshotDigest = snapshot.getSnapshotDigest();
		this.purpose = purpose;
		this.expiresAt = expiresAt;
		this.status = DatasetExportStatus.REQUESTED;
		this.requestedBy = requestedBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.requestedAt = requestedAt;
	}

	static DatasetExport request(
			DatasetVersion datasetVersion,
			DatasetBuildSpecification specification,
			DatasetBuildSnapshot snapshot,
			DatasetVersionValidation validation,
			UUID approvalRequestId,
			DatasetExportPurpose purpose,
			Instant expiresAt,
			String requestedBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant requestedAt) {
		return new DatasetExport(
				datasetVersion,
				specification,
				snapshot,
				validation,
				approvalRequestId,
				purpose,
				expiresAt,
				requestedBy,
				idempotencyKey,
				requestFingerprint,
				requestedAt);
	}

	boolean matchesRequest(String requestedBy, String idempotencyKey, String requestFingerprint) {
		return this.requestedBy.equals(requestedBy)
				&& this.idempotencyKey.equals(idempotencyKey)
				&& this.requestFingerprint.equals(requestFingerprint);
	}

	UUID getDatasetExportId() {
		return this.datasetExportId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getDatasetBuildSpecificationId() {
		return this.datasetBuildSpecificationId;
	}

	UUID getDatasetBuildSnapshotId() {
		return this.datasetBuildSnapshotId;
	}

	UUID getDatasetVersionValidationId() {
		return this.datasetVersionValidationId;
	}

	UUID getApprovalRequestId() {
		return this.approvalRequestId;
	}

	String getExportProfileKey() {
		return this.exportProfileKey;
	}

	String getExportProfileDigest() {
		return this.exportProfileDigest;
	}

	String getSnapshotDigest() {
		return this.snapshotDigest;
	}

	DatasetExportPurpose getPurpose() {
		return this.purpose;
	}

	Instant getExpiresAt() {
		return this.expiresAt;
	}

	DatasetExportStatus getStatus() {
		return this.status;
	}

	String getRequestedBy() {
		return this.requestedBy;
	}

	Instant getRequestedAt() {
		return this.requestedAt;
	}

	long getVersion() {
		return this.version;
	}
}

enum DatasetExportPurpose {
	MODEL_TRAINING
}

enum DatasetExportStatus {
	REQUESTED
}
