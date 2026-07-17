package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "privacy_assessment")
class PrivacyAssessment {

	@Id
	@Column(name = "privacy_assessment_id", nullable = false, updatable = false)
	private UUID privacyAssessmentId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "input_digest", nullable = false, length = 64, updatable = false)
	private String inputDigest;

	@Column(name = "assessment_version", nullable = false, updatable = false)
	private int assessmentVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "assessment_method", nullable = false, length = 32, updatable = false)
	private PrivacyAssessmentMethod assessmentMethod;

	@Column(name = "policy_key", nullable = false, length = 64, updatable = false)
	private String policyKey;

	@Column(name = "policy_digest", nullable = false, length = 64, updatable = false)
	private String policyDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_level", nullable = false, length = 32, updatable = false)
	private PrivacyRiskLevel riskLevel;

	@Enumerated(EnumType.STRING)
	@Column(name = "required_action", nullable = false, length = 64, updatable = false)
	private PrivacyRequiredAction requiredAction;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, length = 64, updatable = false)
	private PrivacyAssessmentReasonCode reasonCode;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(
			name = "privacy_finding",
			joinColumns = @JoinColumn(name = "privacy_assessment_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "finding_type", nullable = false, length = 32)
	private Set<PrivacyFindingType> findingTypes = new HashSet<>();

	@Column(name = "assessed_by", nullable = false, length = 128, updatable = false)
	private String assessedBy;

	@Column(name = "supersedes_assessment_id", updatable = false)
	private UUID supersedesAssessmentId;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "assessed_at", nullable = false, updatable = false)
	private Instant assessedAt;

	protected PrivacyAssessment() {
	}

	private PrivacyAssessment(
			UUID projectId,
			UUID clipAssetId,
			String inputDigest,
			int assessmentVersion,
			PrivacyAssessmentRequest request,
			String assessedBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant assessedAt) {
		this.privacyAssessmentId = UUID.randomUUID();
		this.projectId = projectId;
		this.clipAssetId = clipAssetId;
		this.inputDigest = inputDigest;
		this.assessmentVersion = assessmentVersion;
		this.assessmentMethod = PrivacyAssessmentMethod.HUMAN_REVIEW;
		this.policyKey = PrivacyAssessmentPolicy.POLICY_KEY;
		this.policyDigest = PrivacyAssessmentPolicy.POLICY_DIGEST;
		this.riskLevel = request.riskLevel();
		this.requiredAction = request.requiredAction();
		this.reasonCode = request.reasonCode();
		this.findingTypes.addAll(request.findingTypes());
		this.assessedBy = assessedBy;
		this.supersedesAssessmentId = request.supersedesAssessmentId();
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.assessedAt = assessedAt;
	}

	static PrivacyAssessment assess(
			UUID projectId,
			UUID clipAssetId,
			String inputDigest,
			int assessmentVersion,
			PrivacyAssessmentRequest request,
			String assessedBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant assessedAt) {
		return new PrivacyAssessment(
				projectId,
				clipAssetId,
				inputDigest,
				assessmentVersion,
				request,
				assessedBy,
				idempotencyKey,
				requestFingerprint,
				assessedAt);
	}

	UUID getPrivacyAssessmentId() {
		return this.privacyAssessmentId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	String getInputDigest() {
		return this.inputDigest;
	}

	int getAssessmentVersion() {
		return this.assessmentVersion;
	}

	PrivacyAssessmentMethod getAssessmentMethod() {
		return this.assessmentMethod;
	}

	String getPolicyKey() {
		return this.policyKey;
	}

	String getPolicyDigest() {
		return this.policyDigest;
	}

	PrivacyRiskLevel getRiskLevel() {
		return this.riskLevel;
	}

	PrivacyRequiredAction getRequiredAction() {
		return this.requiredAction;
	}

	PrivacyAssessmentReasonCode getReasonCode() {
		return this.reasonCode;
	}

	Set<PrivacyFindingType> getFindingTypes() {
		return Set.copyOf(this.findingTypes);
	}

	String getAssessedBy() {
		return this.assessedBy;
	}

	UUID getSupersedesAssessmentId() {
		return this.supersedesAssessmentId;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	Instant getAssessedAt() {
		return this.assessedAt;
	}
}
