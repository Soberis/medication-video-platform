package com.medicationvideo.platform.datasetexport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record HandLandmarkTrainingExportPreviewResponse(String schemaVersion, long eligibleEventCount,
		long eventsWithLandmarks, long sampleCount, long eventsWithoutLandmarks, String fileName, String contentType,
		Map<String, Long> reasonCounts) {

	HandLandmarkTrainingExportPreviewResponse {
		reasonCounts = Collections.unmodifiableMap(new LinkedHashMap<>(reasonCounts));
	}

}
