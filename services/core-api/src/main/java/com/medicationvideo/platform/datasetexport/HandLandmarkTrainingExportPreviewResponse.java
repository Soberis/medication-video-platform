package com.medicationvideo.platform.datasetexport;

record HandLandmarkTrainingExportPreviewResponse(String schemaVersion, long handDetectionViewCount,
		long completedViewCount, long viewsWithHand, long sampleCount, long noJobViewCount, long queuedViewCount,
		long runningViewCount, long failedViewCount, long noHandViewCount, String fileName, String mediaType) {

}
