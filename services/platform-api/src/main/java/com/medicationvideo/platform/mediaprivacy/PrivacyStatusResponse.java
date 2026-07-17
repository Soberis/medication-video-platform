package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

public record PrivacyStatusResponse(
		UUID clipAssetId,
		String inputDigest,
		String riskLevel,
		PrivacyRequiredAction requiredAction,
		String privacyReleaseStatus,
		PrivacyAssessmentResponse currentAssessment) {

	static PrivacyStatusResponse unknown(UUID clipAssetId, String inputDigest) {
		return new PrivacyStatusResponse(clipAssetId, inputDigest, "UNKNOWN", null, "BLOCKED", null);
	}

	static PrivacyStatusResponse from(PrivacyAssessment assessment) {
		return new PrivacyStatusResponse(
				assessment.getClipAssetId(),
				assessment.getInputDigest(),
				assessment.getRiskLevel().name(),
				assessment.getRequiredAction(),
				"BLOCKED",
				PrivacyAssessmentResponse.from(assessment));
	}
}
