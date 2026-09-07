package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only, approved human annotation segment for downstream export formats.
 */
public record HumanAnnotationExportSegment(UUID captureGroupId, UUID annotationTaskId, UUID annotationRevisionId,
		int revisionNumber, UUID cameraStreamId, UUID primaryClipAssetId, int primaryCameraNumber,
		String clipAssetSha256, String annotationSchemaVersion, UUID labelSchemaId, String labelSchemaDigest,
		String inputVersion, long segmentStartMs, long segmentEndMs, String label, String annotationNote,
		String documentSha256, Instant submittedAt, UUID annotationReviewId, String reviewDecision,
		Instant reviewedAt) {
}
