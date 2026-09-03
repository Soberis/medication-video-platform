package com.medicationvideo.platform.datasetexport;

import java.time.Instant;

record HandLandmarkTrainingExportPreviewResponse(String schemaVersion, long handDetectionViewCount,
		long completedViewCount, long viewsWithHand, long sampleCount, long noJobViewCount, long queuedViewCount,
		long runningViewCount, long failedViewCount, long noHandViewCount, String fileName, Instant generatedAt,
		String mediaType) {

}
