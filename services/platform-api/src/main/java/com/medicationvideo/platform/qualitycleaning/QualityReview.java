package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "quality_review")
class QualityReview {

	@Id
	@Column(name = "quality_review_id", nullable = false, updatable = false)
	private UUID qualityReviewId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision", nullable = false, length = 32)
	private QualityReviewDecision decision;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, length = 64)
	private QualityReviewReasonCode reasonCode;

	@Column(name = "reviewed_by", nullable = false, length = 128)
	private String reviewedBy;

	@Column(name = "reviewed_at", nullable = false)
	private Instant reviewedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected QualityReview() {
	}

	private QualityReview(
			UUID projectId,
			UUID clipAssetId,
			QualityReviewDecision decision,
			QualityReviewReasonCode reasonCode,
			String reviewedBy,
			Instant reviewedAt) {
		this.qualityReviewId = UUID.randomUUID();
		this.projectId = projectId;
		this.clipAssetId = clipAssetId;
		apply(decision, reasonCode, reviewedBy, reviewedAt);
	}

	static QualityReview create(
			UUID projectId,
			UUID clipAssetId,
			QualityReviewDecision decision,
			QualityReviewReasonCode reasonCode,
			String reviewedBy,
			Instant reviewedAt) {
		return new QualityReview(projectId, clipAssetId, decision, reasonCode, reviewedBy, reviewedAt);
	}

	void revise(
			QualityReviewDecision newDecision,
			QualityReviewReasonCode newReasonCode,
			String actorId,
			Instant changedAt) {
		apply(newDecision, newReasonCode, actorId, changedAt);
	}

	boolean hasSameConclusion(QualityReviewDecision candidateDecision, QualityReviewReasonCode candidateReason) {
		return this.decision == candidateDecision && this.reasonCode == candidateReason;
	}

	private void apply(
			QualityReviewDecision newDecision,
			QualityReviewReasonCode newReasonCode,
			String actorId,
			Instant changedAt) {
		if (!newReasonCode.supports(newDecision)) {
			throw new QualityReviewException(
					"INVALID_QUALITY_REASON",
					"The quality reason is not valid for the selected decision.",
					400);
		}
		this.decision = newDecision;
		this.reasonCode = newReasonCode;
		this.reviewedBy = actorId;
		this.reviewedAt = changedAt;
	}

	UUID getQualityReviewId() {
		return this.qualityReviewId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	QualityReviewDecision getDecision() {
		return this.decision;
	}

	QualityReviewReasonCode getReasonCode() {
		return this.reasonCode;
	}

	String getReviewedBy() {
		return this.reviewedBy;
	}

	Instant getReviewedAt() {
		return this.reviewedAt;
	}

	long getVersion() {
		return this.version;
	}
}

enum QualityReviewDecision {
	QUALIFIED,
	REJECTED,
	NEEDS_REVIEW
}

enum QualityReviewReasonCode {
	MEETS_REQUIREMENTS(QualityReviewDecision.QUALIFIED),
	SENSITIVE_CONTENT(QualityReviewDecision.REJECTED),
	BLURRY_OR_OUT_OF_FOCUS(QualityReviewDecision.REJECTED),
	BLACK_OR_CORRUPTED_FRAMES(QualityReviewDecision.REJECTED),
	SEVERE_OCCLUSION(QualityReviewDecision.REJECTED),
	INCOMPLETE_ACTION(QualityReviewDecision.REJECTED),
	WRONG_SCENE(QualityReviewDecision.REJECTED),
	DUPLICATE(QualityReviewDecision.REJECTED),
	OTHER_QUALITY_FAILURE(QualityReviewDecision.REJECTED),
	UNCERTAIN_QUALITY(QualityReviewDecision.NEEDS_REVIEW),
	METADATA_MISMATCH(QualityReviewDecision.NEEDS_REVIEW);

	private final QualityReviewDecision decision;

	QualityReviewReasonCode(QualityReviewDecision decision) {
		this.decision = decision;
	}

	boolean supports(QualityReviewDecision candidateDecision) {
		return this.decision == candidateDecision;
	}
}
