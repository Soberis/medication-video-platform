package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "annotation_review")
class AnnotationReview {

	@Id
	@Column(name = "annotation_review_id", nullable = false, updatable = false)
	private UUID annotationReviewId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "annotation_revision_id", nullable = false, updatable = false)
	private UUID annotationRevisionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision", nullable = false, updatable = false, length = 32)
	private AnnotationReviewDecision decision;

	@Column(name = "review_note", updatable = false, length = 1000)
	private String reviewNote;

	@Column(name = "reviewed_by", nullable = false, updatable = false, length = 128)
	private String reviewedBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "reviewed_at", nullable = false, updatable = false)
	private Instant reviewedAt;

	protected AnnotationReview() {
	}

	private AnnotationReview(
			UUID projectId,
			UUID annotationRevisionId,
			AnnotationReviewDecision decision,
			String reviewNote,
			String reviewedBy,
			String idempotencyKey,
			Instant reviewedAt) {
		this.annotationReviewId = UUID.randomUUID();
		this.projectId = projectId;
		this.annotationRevisionId = annotationRevisionId;
		this.decision = decision;
		this.reviewNote = reviewNote == null || reviewNote.isBlank() ? null : reviewNote.trim();
		this.reviewedBy = reviewedBy;
		this.idempotencyKey = idempotencyKey;
		this.reviewedAt = reviewedAt;
	}

	static AnnotationReview record(
			AnnotationRevision revision,
			AnnotationReviewDecision decision,
			String reviewNote,
			String reviewedBy,
			String idempotencyKey,
			Instant reviewedAt) {
		return new AnnotationReview(
				revision.getProjectId(),
				revision.getAnnotationRevisionId(),
				decision,
				reviewNote,
				reviewedBy,
				idempotencyKey,
				reviewedAt);
	}

	UUID getAnnotationReviewId() {
		return this.annotationReviewId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getAnnotationRevisionId() {
		return this.annotationRevisionId;
	}

	AnnotationReviewDecision getDecision() {
		return this.decision;
	}

	String getReviewNote() {
		return this.reviewNote;
	}

	String getReviewedBy() {
		return this.reviewedBy;
	}

	Instant getReviewedAt() {
		return this.reviewedAt;
	}

	boolean matches(AnnotationReviewDecision decision, String reviewNote) {
		var normalizedNote = reviewNote == null || reviewNote.isBlank() ? null : reviewNote.trim();
		return this.decision == decision && java.util.Objects.equals(this.reviewNote, normalizedNote);
	}
}

enum AnnotationReviewDecision {
	APPROVED,
	REJECTED
}
