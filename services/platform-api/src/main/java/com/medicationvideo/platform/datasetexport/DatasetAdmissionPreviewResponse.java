package com.medicationvideo.platform.datasetexport;

import java.util.List;
import java.util.UUID;

record DatasetAdmissionPreviewResponse(
        UUID captureSessionId,
        boolean eligible,
        String assemblyStatus,
        String qualityStatus,
        int expectedCameraCount,
        int receivedCameraCount,
        List<String> blockingReasons,
        List<StreamDatasetAdmissionPreview> streams) {
}

record StreamDatasetAdmissionPreview(
        UUID cameraStreamId,
        UUID clipAssetId,
        int cameraIndex,
        boolean eligible,
        String streamStatus,
        String streamQualityStatus,
        String assetStatus,
        boolean assetDeleted,
        String qualityReviewDecision,
        UUID annotationRevisionId,
        String annotationReviewDecision,
        String postCleaningDecision,
        String annotationDocumentSha256,
        String labelSchemaDigest,
        List<String> blockingReasons) {
}
