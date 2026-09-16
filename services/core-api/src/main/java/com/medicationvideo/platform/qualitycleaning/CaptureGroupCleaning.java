package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only historical CaptureGroup cleaning fact.
 *
 * <p>
 * New cleaning facts are no longer produced. D1 keeps the table and query surface so
 * previously recorded facts remain available for compatibility and audit display.
 * </p>
 */
@Entity
@Table(name = "capture_group_cleaning")
class CaptureGroupCleaning {

	@Id
	@Column(name = "capture_group_cleaning_id", nullable = false, updatable = false)
	private UUID captureGroupCleaningId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "capture_group_id", nullable = false, updatable = false)
	private UUID captureGroupId;

	@Column(name = "annotation_revision_id", nullable = false, updatable = false)
	private UUID annotationRevisionId;

	@Column(name = "annotation_review_id", nullable = false, updatable = false)
	private UUID annotationReviewId;

	@Column(name = "annotation_review_decision", nullable = false, updatable = false, length = 32)
	private String annotationReviewDecision;

	@Column(name = "annotation_reviewed_by", nullable = false, updatable = false, length = 128)
	private String annotationReviewedBy;

	@Column(name = "annotation_reviewed_at", nullable = false, updatable = false)
	private Instant annotationReviewedAt;

	@Column(name = "group_quality_review_id", nullable = false, updatable = false)
	private UUID groupQualityReviewId;

	@Column(name = "group_quality_result", nullable = false, updatable = false, length = 32)
	private String groupQualityResult;

	@Column(name = "group_quality_revision", nullable = false, updatable = false)
	private long groupQualityRevision;

	@Column(name = "primary_clip_asset_id", nullable = false, updatable = false)
	private UUID primaryClipAssetId;

	@Column(name = "primary_camera_number", nullable = false, updatable = false)
	private int primaryCameraNumber;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "primary_interval", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> primaryInterval;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "view_evidence", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> viewEvidence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "participating_views", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> participatingViews;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "excluded_views", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> excludedViews;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "mapping_provenance", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> mappingProvenance;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "candidate_provenance", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> candidateProvenance;

	@Column(name = "rule_set_key", nullable = false, updatable = false, length = 64)
	private String ruleSetKey;

	@Column(name = "rule_set_version", nullable = false, updatable = false, length = 64)
	private String ruleSetVersion;

	@Column(name = "rule_set_digest", nullable = false, updatable = false, length = 64)
	private String ruleSetDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false, updatable = false, length = 16)
	private CleaningOutcome outcome;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "reason_codes", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<String> reasonCodes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "executed_checks", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<String> executedChecks;

	@Column(name = "input_fingerprint", nullable = false, updatable = false, length = 64)
	private String inputFingerprint;

	@Column(name = "evaluated_by", nullable = false, updatable = false, length = 128)
	private String evaluatedBy;

	@Column(name = "authorization_basis", nullable = false, updatable = false, length = 128)
	private String authorizationBasis;

	@Column(name = "evaluated_at", nullable = false, updatable = false)
	private Instant evaluatedAt;

	protected CaptureGroupCleaning() {
	}

	UUID getCaptureGroupCleaningId() {
		return this.captureGroupCleaningId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getCaptureGroupId() {
		return this.captureGroupId;
	}

	UUID getAnnotationRevisionId() {
		return this.annotationRevisionId;
	}

	UUID getAnnotationReviewId() {
		return this.annotationReviewId;
	}

	String getAnnotationReviewDecision() {
		return this.annotationReviewDecision;
	}

	String getAnnotationReviewedBy() {
		return this.annotationReviewedBy;
	}

	Instant getAnnotationReviewedAt() {
		return this.annotationReviewedAt;
	}

	UUID getGroupQualityReviewId() {
		return this.groupQualityReviewId;
	}

	String getGroupQualityResult() {
		return this.groupQualityResult;
	}

	long getGroupQualityRevision() {
		return this.groupQualityRevision;
	}

	UUID getPrimaryClipAssetId() {
		return this.primaryClipAssetId;
	}

	int getPrimaryCameraNumber() {
		return this.primaryCameraNumber;
	}

	List<Map<String, Object>> getPrimaryInterval() {
		return this.primaryInterval;
	}

	List<Map<String, Object>> getViewEvidence() {
		return this.viewEvidence;
	}

	List<Map<String, Object>> getParticipatingViews() {
		return this.participatingViews;
	}

	List<Map<String, Object>> getExcludedViews() {
		return this.excludedViews;
	}

	List<Map<String, Object>> getMappingProvenance() {
		return this.mappingProvenance;
	}

	List<Map<String, Object>> getCandidateProvenance() {
		return this.candidateProvenance;
	}

	String getRuleSetKey() {
		return this.ruleSetKey;
	}

	String getRuleSetVersion() {
		return this.ruleSetVersion;
	}

	String getRuleSetDigest() {
		return this.ruleSetDigest;
	}

	CleaningOutcome getOutcome() {
		return this.outcome;
	}

	List<String> getReasonCodes() {
		return this.reasonCodes;
	}

	List<String> getExecutedChecks() {
		return this.executedChecks;
	}

	String getInputFingerprint() {
		return this.inputFingerprint;
	}

	String getEvaluatedBy() {
		return this.evaluatedBy;
	}

	String getAuthorizationBasis() {
		return this.authorizationBasis;
	}

	Instant getEvaluatedAt() {
		return this.evaluatedAt;
	}

}
