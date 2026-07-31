package com.medicationvideo.platform.annotationreview;

import java.util.UUID;

import com.medicationvideo.platform.qualitycleaning.PostCleaningResultSummary;

/**
 * Latest annotation, review and post-cleaning facts for one clip asset.
 */
public record AnnotationDatasetAdmissionFact(UUID clipAssetId, UUID annotationRevisionId, String reviewDecision,
		String documentSha256, String labelSchemaDigest, PostCleaningResultSummary postCleaningResult) {
}
