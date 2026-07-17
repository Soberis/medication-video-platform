package com.medicationvideo.platform.mediaprivacy;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PrivacyAssessmentRequest(
		@NotNull UUID clipAssetId,
		UUID supersedesAssessmentId,
		@NotNull PrivacyRiskLevel riskLevel,
		@NotNull PrivacyRequiredAction requiredAction,
		@NotNull PrivacyAssessmentReasonCode reasonCode,
		@NotNull @Size(max = 9) Set<@NotNull PrivacyFindingType> findingTypes) {

	public PrivacyAssessmentRequest {
		findingTypes = findingTypes == null ? null : Set.copyOf(findingTypes);
	}
}
