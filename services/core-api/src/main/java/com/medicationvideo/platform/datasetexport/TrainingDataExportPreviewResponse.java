package com.medicationvideo.platform.datasetexport;

record TrainingDataExportPreviewResponse(String schemaVersion, long eligibleVideoCount, long candidateObservationCount,
		long trainingRowCount, long unlabeledCandidateObservationCount, String trainingRowDefinition, String fileName,
		String contentType, boolean includesMp4) {
}
