package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_version_validation")
class DatasetVersionValidation {

	static final String PROFILE_VERSION = "DATASET_FREEZE_VALIDATION_V1";
	static final List<String> EXECUTED_CHECKS = List.of("CURRENT_ADMISSION", "REFERENCE_INTEGRITY", "SNAPSHOT_DIGEST",
			"SPLIT_ISOLATION", "BUILD_CONTRACT");

	@Id
	@Column(name = "dataset_version_validation_id", nullable = false, updatable = false)
	private UUID datasetVersionValidationId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "dataset_build_snapshot_id", nullable = false, updatable = false)
	private UUID datasetBuildSnapshotId;

	@Column(name = "validation_profile_version", nullable = false, updatable = false, length = 64)
	private String validationProfileVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false, updatable = false, length = 16)
	private DatasetVersionValidationOutcome outcome;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "reason_codes", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<String> reasonCodes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "executed_checks", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<String> executedChecks;

	@Column(name = "validated_snapshot_digest", nullable = false, updatable = false, length = 64)
	private String validatedSnapshotDigest;

	@Column(name = "validated_by", nullable = false, updatable = false, length = 128)
	private String validatedBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "validated_at", nullable = false, updatable = false)
	private Instant validatedAt;

	protected DatasetVersionValidation() {
	}

	private DatasetVersionValidation(DatasetVersion version, DatasetBuildSnapshot snapshot, List<String> reasonCodes,
			String validatedSnapshotDigest, String validatedBy, String idempotencyKey, String requestFingerprint,
			Instant validatedAt) {
		this.datasetVersionValidationId = UUID.randomUUID();
		this.datasetVersionId = version.getDatasetVersionId();
		this.projectId = version.getProjectId();
		this.datasetBuildSnapshotId = snapshot.getDatasetBuildSnapshotId();
		this.validationProfileVersion = PROFILE_VERSION;
		this.outcome = reasonCodes.isEmpty() ? DatasetVersionValidationOutcome.PASS
				: DatasetVersionValidationOutcome.FAIL;
		this.reasonCodes = List.copyOf(reasonCodes);
		this.executedChecks = EXECUTED_CHECKS;
		this.validatedSnapshotDigest = validatedSnapshotDigest;
		this.validatedBy = validatedBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.validatedAt = validatedAt;
	}

	static DatasetVersionValidation record(DatasetVersion version, DatasetBuildSnapshot snapshot,
			List<String> reasonCodes, String validatedSnapshotDigest, String validatedBy, String idempotencyKey,
			String requestFingerprint, Instant validatedAt) {
		return new DatasetVersionValidation(version, snapshot, reasonCodes, validatedSnapshotDigest, validatedBy,
				idempotencyKey, requestFingerprint, validatedAt);
	}

	boolean matchesRequest(String fingerprint) {
		return this.requestFingerprint.equals(fingerprint);
	}

	boolean matchesSnapshot(DatasetBuildSnapshot snapshot) {
		return this.datasetBuildSnapshotId.equals(snapshot.getDatasetBuildSnapshotId())
				&& this.validatedSnapshotDigest.equals(snapshot.getSnapshotDigest());
	}

	UUID getDatasetVersionValidationId() {
		return this.datasetVersionValidationId;
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getDatasetBuildSnapshotId() {
		return this.datasetBuildSnapshotId;
	}

	String getValidationProfileVersion() {
		return this.validationProfileVersion;
	}

	DatasetVersionValidationOutcome getOutcome() {
		return this.outcome;
	}

	List<String> getReasonCodes() {
		return this.reasonCodes;
	}

	List<String> getExecutedChecks() {
		return this.executedChecks;
	}

	String getValidatedSnapshotDigest() {
		return this.validatedSnapshotDigest;
	}

	String getValidatedBy() {
		return this.validatedBy;
	}

	Instant getValidatedAt() {
		return this.validatedAt;
	}

}

enum DatasetVersionValidationOutcome {

	PASS, FAIL

}
