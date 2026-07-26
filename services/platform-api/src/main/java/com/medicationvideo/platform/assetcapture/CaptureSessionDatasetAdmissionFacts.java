package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

/**
 * Immutable asset facts used by the dataset module to evaluate a capture session.
 */
public record CaptureSessionDatasetAdmissionFacts(
        UUID captureSessionId,
        int expectedCameraCount,
        int receivedCameraCount,
        String assemblyStatus,
        String qualityStatus,
        List<Stream> streams) {

    public record Stream(
            UUID cameraStreamId,
            UUID clipAssetId,
            int cameraIndex,
            String streamStatus,
            String qualityStatus,
            String assetStatus,
            boolean assetDeleted,
            String assetSha256) {
    }
}
