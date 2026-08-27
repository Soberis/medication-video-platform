package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CaptureGroupCleaningSummary(UUID captureGroupCleaningId, UUID projectId, UUID captureGroupId,
		UUID annotationRevisionId, UUID annotationReviewId, String annotationReviewDecision,
		String annotationReviewedBy, Instant annotationReviewedAt, UUID groupQualityReviewId, String groupQualityResult,
		long groupQualityRevision, UUID primaryClipAssetId, int primaryCameraNumber,
		List<Map<String, Object>> primaryInterval, List<Map<String, Object>> viewEvidence,
		List<Map<String, Object>> participatingViews, List<Map<String, Object>> excludedViews,
		List<Map<String, Object>> mappingProvenance, List<Map<String, Object>> candidateProvenance, String ruleSetKey,
		String ruleSetVersion, String ruleSetDigest, String outcome, List<String> reasonCodes,
		List<String> executedChecks, String inputFingerprint, String evaluatedBy, String authorizationBasis,
		Instant evaluatedAt) {

	static CaptureGroupCleaningSummary from(CaptureGroupCleaning cleaning) {
		return new CaptureGroupCleaningSummary(cleaning.getCaptureGroupCleaningId(), cleaning.getProjectId(),
				cleaning.getCaptureGroupId(), cleaning.getAnnotationRevisionId(), cleaning.getAnnotationReviewId(),
				cleaning.getAnnotationReviewDecision(), cleaning.getAnnotationReviewedBy(),
				cleaning.getAnnotationReviewedAt(), cleaning.getGroupQualityReviewId(),
				cleaning.getGroupQualityResult(), cleaning.getGroupQualityRevision(), cleaning.getPrimaryClipAssetId(),
				cleaning.getPrimaryCameraNumber(), cleaning.getPrimaryInterval(), cleaning.getViewEvidence(),
				cleaning.getParticipatingViews(), cleaning.getExcludedViews(), cleaning.getMappingProvenance(),
				cleaning.getCandidateProvenance(), cleaning.getRuleSetKey(), cleaning.getRuleSetVersion(),
				cleaning.getRuleSetDigest(), cleaning.getOutcome().name(), cleaning.getReasonCodes(),
				cleaning.getExecutedChecks(), cleaning.getInputFingerprint(), cleaning.getEvaluatedBy(),
				cleaning.getAuthorizationBasis(), cleaning.getEvaluatedAt());
	}

}
