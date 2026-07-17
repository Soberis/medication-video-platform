package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record PrivacyAssessmentResponse(
		UUID privacyAssessmentId,
		UUID projectId,
		UUID clipAssetId,
		String inputDigest,
		int assessmentVersion,
		PrivacyAssessmentMethod assessmentMethod,
		String policyKey,
		String policyDigest,
		PrivacyRiskLevel riskLevel,
		PrivacyRequiredAction requiredAction,
		String privacyReleaseStatus,
		List<PrivacyFindingType> findingTypes,
		PrivacyAssessmentReasonCode reasonCode,
		String assessedBy,
		UUID supersedesAssessmentId,
		Instant assessedAt) {

	static PrivacyAssessmentResponse from(PrivacyAssessment assessment) {
		return new PrivacyAssessmentResponse(
				assessment.getPrivacyAssessmentId(),
				assessment.getProjectId(),
				assessment.getClipAssetId(),
				assessment.getInputDigest(),
				assessment.getAssessmentVersion(),
				assessment.getAssessmentMethod(),
				assessment.getPolicyKey(),
				assessment.getPolicyDigest(),
				assessment.getRiskLevel(),
				assessment.getRequiredAction(),
				"BLOCKED",
				assessment.getFindingTypes().stream()
						.sorted(Comparator.comparing(Enum::name))
						.toList(),
				assessment.getReasonCode(),
				assessment.getAssessedBy(),
				assessment.getSupersedesAssessmentId(),
				assessment.getAssessedAt());
	}
}
