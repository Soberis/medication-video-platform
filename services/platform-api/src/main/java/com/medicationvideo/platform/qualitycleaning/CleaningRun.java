package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "cleaning_run")
class CleaningRun {

	@Id
	@Column(name = "cleaning_run_id", nullable = false, updatable = false)
	private UUID cleaningRunId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "annotation_revision_id", nullable = false, updatable = false)
	private UUID annotationRevisionId;

	@Column(name = "annotation_review_id", nullable = false, updatable = false)
	private UUID annotationReviewId;

	@Column(name = "review_decision", nullable = false, updatable = false, length = 32)
	private String reviewDecision;

	@Column(name = "cleaning_rule_set_id", nullable = false, updatable = false)
	private UUID cleaningRuleSetId;

	@Column(name = "rule_set_digest", nullable = false, updatable = false, length = 64)
	private String ruleSetDigest;

	@Column(name = "input_fingerprint", updatable = false, length = 64)
	private String inputFingerprint;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "cleaning_rule_set_id",
			referencedColumnName = "cleaning_rule_set_id",
			insertable = false,
			updatable = false)
	private CleaningRuleSet ruleSet;

	@Column(name = "asset_sha256", nullable = false, updatable = false, length = 64)
	private String assetSha256;

	@Column(name = "document_sha256", nullable = false, updatable = false, length = 64)
	private String documentSha256;

	@Column(name = "label_schema_id", nullable = false, updatable = false)
	private UUID labelSchemaId;

	@Column(name = "label_schema_digest", nullable = false, updatable = false, length = 64)
	private String labelSchemaDigest;

	@Column(name = "quality_review_version", nullable = false, updatable = false)
	private long qualityReviewVersion;

	@Column(name = "media_report_id", updatable = false)
	private UUID mediaReportId;

	@Column(name = "media_input_digest", updatable = false, length = 64)
	private String mediaInputDigest;

	@Column(name = "media_recipe_digest", updatable = false, length = 64)
	private String mediaRecipeDigest;

	@Column(name = "verified_duration_microseconds", updatable = false)
	private Long verifiedDurationMicroseconds;

	@Column(name = "trigger_type", nullable = false, updatable = false, length = 32)
	private String triggerType;

	@Column(name = "run_status", nullable = false, updatable = false, length = 32)
	private String runStatus;

	@Column(name = "triggered_by", nullable = false, updatable = false, length = 128)
	private String triggeredBy;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "completed_at", nullable = false, updatable = false)
	private Instant completedAt;

	protected CleaningRun() {
	}

	private CleaningRun(
			PostCleaningService.EvaluationCommand command,
			CleaningRuleSet ruleSet,
			String inputFingerprint,
			Instant evaluatedAt) {
		this.cleaningRunId = UUID.randomUUID();
		this.projectId = command.projectId();
		this.clipAssetId = command.clipAssetId();
		this.annotationRevisionId = command.annotationRevisionId();
		this.annotationReviewId = command.annotationReviewId();
		this.reviewDecision = "APPROVED";
		this.cleaningRuleSetId = ruleSet.getCleaningRuleSetId();
		this.ruleSetDigest = ruleSet.getRuleSetDigest();
		this.inputFingerprint = inputFingerprint;
		this.ruleSet = ruleSet;
		this.assetSha256 = command.assetSha256();
		this.documentSha256 = command.documentSha256();
		this.labelSchemaId = command.revisionLabelSchemaId();
		this.labelSchemaDigest = command.revisionLabelSchemaDigest();
		this.qualityReviewVersion = command.qualityReviewVersion();
		this.mediaReportId = command.mediaReportId();
		this.mediaInputDigest = command.mediaInputDigest();
		this.mediaRecipeDigest = command.mediaRecipeDigest();
		this.verifiedDurationMicroseconds = command.verifiedDurationMicroseconds();
		this.triggerType = "ANNOTATION_APPROVAL";
		this.runStatus = "SUCCEEDED";
		this.triggeredBy = command.actorId();
		this.startedAt = evaluatedAt;
		this.completedAt = evaluatedAt;
	}

	static CleaningRun complete(
			PostCleaningService.EvaluationCommand command,
			CleaningRuleSet ruleSet,
			String inputFingerprint,
			Instant evaluatedAt) {
		return new CleaningRun(command, ruleSet, inputFingerprint, evaluatedAt);
	}

	UUID getCleaningRunId() {
		return this.cleaningRunId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getAnnotationRevisionId() {
		return this.annotationRevisionId;
	}

	UUID getCleaningRuleSetId() {
		return this.cleaningRuleSetId;
	}

	CleaningRuleSet getRuleSet() {
		return this.ruleSet;
	}

	Instant getCompletedAt() {
		return this.completedAt;
	}
}
