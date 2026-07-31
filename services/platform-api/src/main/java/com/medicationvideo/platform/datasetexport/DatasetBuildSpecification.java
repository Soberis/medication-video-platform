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
@Table(name = "dataset_build_specification")
class DatasetBuildSpecification {

	@Id
	@Column(name = "dataset_build_specification_id", nullable = false, updatable = false)
	private UUID datasetBuildSpecificationId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Enumerated(EnumType.STRING)
	@Column(name = "selection_rule_version", nullable = false, updatable = false, length = 64)
	private DatasetSelectionRuleVersion selectionRuleVersion;

	@Column(name = "label_schema_digest", nullable = false, updatable = false, length = 64)
	private String labelSchemaDigest;

	@Column(name = "cleaning_rule_set_digest", nullable = false, updatable = false, length = 64)
	private String cleaningRuleSetDigest;

	@Column(name = "dataset_split_plan_id", nullable = false, updatable = false)
	private UUID datasetSplitPlanId;

	@Column(name = "export_profile_key", nullable = false, updatable = false, length = 64)
	private String exportProfileKey;

	@Column(name = "export_profile_digest", nullable = false, updatable = false, length = 64)
	private String exportProfileDigest;

	@Column(name = "created_by", nullable = false, updatable = false, length = 128)
	private String createdBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected DatasetBuildSpecification() {
	}

	private DatasetBuildSpecification(DatasetVersion version, String labelSchemaDigest, String cleaningRuleSetDigest,
			DatasetSplitPlan splitPlan, String createdBy, String idempotencyKey, String requestFingerprint,
			Instant createdAt) {
		this.datasetBuildSpecificationId = UUID.randomUUID();
		this.datasetVersionId = version.getDatasetVersionId();
		this.projectId = version.getProjectId();
		this.selectionRuleVersion = DatasetSelectionRuleVersion.EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1;
		this.labelSchemaDigest = labelSchemaDigest;
		this.cleaningRuleSetDigest = cleaningRuleSetDigest;
		this.datasetSplitPlanId = splitPlan.getDatasetSplitPlanId();
		this.exportProfileKey = DatasetExportProfile.CURRENT_KEY;
		this.exportProfileDigest = DatasetExportProfile.CURRENT_DIGEST;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static DatasetBuildSpecification create(DatasetVersion version, String labelSchemaDigest,
			String cleaningRuleSetDigest, DatasetSplitPlan splitPlan, String createdBy, String idempotencyKey,
			String requestFingerprint, Instant createdAt) {
		return new DatasetBuildSpecification(version, labelSchemaDigest, cleaningRuleSetDigest, splitPlan, createdBy,
				idempotencyKey, requestFingerprint, createdAt);
	}

	boolean matchesCreateRequest(String fingerprint) {
		return this.requestFingerprint.equals(fingerprint);
	}

	UUID getDatasetBuildSpecificationId() {
		return this.datasetBuildSpecificationId;
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	DatasetSelectionRuleVersion getSelectionRuleVersion() {
		return this.selectionRuleVersion;
	}

	String getLabelSchemaDigest() {
		return this.labelSchemaDigest;
	}

	String getCleaningRuleSetDigest() {
		return this.cleaningRuleSetDigest;
	}

	UUID getDatasetSplitPlanId() {
		return this.datasetSplitPlanId;
	}

	String getExportProfileKey() {
		return this.exportProfileKey;
	}

	String getExportProfileDigest() {
		return this.exportProfileDigest;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

}

enum DatasetSelectionRuleVersion {

	EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1

}
