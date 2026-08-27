package com.medicationvideo.platform.datasetexport;

record TrainingDataV3ExportPreviewResponse(String schemaVersion, long eligibleGroupCount, long eventCount,
		String fileName, String contentType, String splitUnit, boolean includesMp4) {
}
