package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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
@Table(name = "capture_group_quality_review")
class CaptureGroupQualityReview {

	@Id
	@Column(name = "capture_group_quality_review_id", nullable = false, updatable = false)
	private UUID captureGroupQualityReviewId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "capture_group_id", nullable = false, updatable = false)
	private UUID captureGroupId;

	@Enumerated(EnumType.STRING)
	@Column(name = "result", nullable = false, updatable = false, length = 32)
	private CaptureGroupQualityResult result;

	@Column(name = "reason", nullable = false, updatable = false, length = 1000)
	private String reason;

	@Column(name = "reviewer_actor_id", nullable = false, updatable = false, length = 128)
	private String reviewerActorId;

	@Column(name = "revision", nullable = false, updatable = false)
	private long revision;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "confirmed_view_set", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> confirmedViewSet;

	@Column(name = "input_version", nullable = false, updatable = false, length = 128)
	private String inputVersion;

	@Column(name = "confirmed_view_set_sha256", nullable = false, updatable = false, length = 64)
	private String confirmedViewSetSha256;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "decided_at", nullable = false, updatable = false)
	private Instant decidedAt;

	protected CaptureGroupQualityReview() {
	}

	private CaptureGroupQualityReview(UUID projectId, UUID captureGroupId, CaptureGroupQualityResult result,
			String reason, String reviewerActorId, long revision, Map<String, Object> confirmedViewSet,
			String inputVersion, String confirmedViewSetSha256, String idempotencyKey, Instant decidedAt) {
		this.captureGroupQualityReviewId = UUID.randomUUID();
		this.projectId = projectId;
		this.captureGroupId = captureGroupId;
		this.result = result;
		this.reason = reason;
		this.reviewerActorId = reviewerActorId;
		this.revision = revision;
		this.confirmedViewSet = Map.copyOf(confirmedViewSet);
		this.inputVersion = inputVersion;
		this.confirmedViewSetSha256 = confirmedViewSetSha256;
		this.idempotencyKey = idempotencyKey;
		this.decidedAt = decidedAt;
	}

	static CaptureGroupQualityReview record(UUID projectId, UUID captureGroupId, CaptureGroupQualityResult result,
			String reason, String reviewerActorId, long revision, Map<String, Object> confirmedViewSet,
			String inputVersion, String confirmedViewSetSha256, String idempotencyKey, Instant decidedAt) {
		return new CaptureGroupQualityReview(projectId, captureGroupId, result, reason, reviewerActorId, revision,
				confirmedViewSet, inputVersion, confirmedViewSetSha256, idempotencyKey, decidedAt);
	}

	boolean matchesSubmission(CaptureGroupQualityResult candidateResult, String candidateReason) {
		return this.result == candidateResult && Objects.equals(this.reason, candidateReason);
	}

	UUID getCaptureGroupQualityReviewId() {
		return this.captureGroupQualityReviewId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getCaptureGroupId() {
		return this.captureGroupId;
	}

	CaptureGroupQualityResult getResult() {
		return this.result;
	}

	String getReason() {
		return this.reason;
	}

	String getReviewerActorId() {
		return this.reviewerActorId;
	}

	long getRevision() {
		return this.revision;
	}

	Map<String, Object> getConfirmedViewSet() {
		return this.confirmedViewSet;
	}

	String getInputVersion() {
		return this.inputVersion;
	}

	String getConfirmedViewSetSha256() {
		return this.confirmedViewSetSha256;
	}

	String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	Instant getDecidedAt() {
		return this.decidedAt;
	}

}
