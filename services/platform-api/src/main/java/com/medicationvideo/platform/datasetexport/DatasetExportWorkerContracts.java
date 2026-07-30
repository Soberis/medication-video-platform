package com.medicationvideo.platform.datasetexport;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.medicationvideo.platform.annotationreview.AnnotationExportQueryService.AnnotationExportSchema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

record DatasetExportLeaseRequest(
		@NotBlank
		@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$") String workerId) {
}

record DatasetExportLeaseResponse(
		UUID exportId,
		int attempt,
		Instant leaseUntil,
		long heartbeatIntervalSeconds,
		Instant inputUrlsExpireAt,
		long outputPartSizeBytes,
		String packageFileName,
		String snapshotDigest,
		String exportProfileKey,
		String exportProfileDigest,
		AnnotationExportSchema labelSchema,
		List<DatasetExportSessionInput> sessions) {

	DatasetExportLeaseResponse {
		sessions = List.copyOf(sessions);
	}
}

record DatasetExportSessionInput(
		UUID captureSessionId,
		String split,
		List<DatasetExportStreamInput> streams) {

	DatasetExportSessionInput {
		streams = List.copyOf(streams);
	}
}

record DatasetExportStreamInput(
		UUID cameraStreamId,
		UUID clipAssetId,
		int cameraIndex,
		String contentType,
		long sizeBytes,
		String sha256,
		URI inputUrl,
		String videoPath,
		UUID annotationRevisionId,
		String annotationSchemaVersion,
		String annotationDocumentSha256,
		Map<String, Object> annotationDocument) {

	DatasetExportStreamInput {
		annotationDocument = Map.copyOf(annotationDocument);
	}
}

record DatasetExportHeartbeatResponse(
		UUID exportId,
		int attempt,
		Instant leaseUntil,
		long heartbeatIntervalSeconds) {
}

record DatasetExportPartAuthorizationResponse(
		UUID exportId,
		int attempt,
		int partNumber,
		URI uploadUrl,
		Instant expiresAt) {
}

record DatasetExportResultAcceptedResponse(
		UUID messageId,
		UUID exportId,
		int attempt,
		DatasetExportResultStatus status,
		DatasetExportStatus exportStatus,
		Instant processedAt) {
}

record DatasetExportDownloadAuthorizationResponse(
		UUID datasetExportId,
		UUID exportArtifactId,
		URI downloadUrl,
		Instant expiresAt,
		String fileName,
		String mediaType,
		long sizeBytes,
		String sha256) {
}

record ExportWorkerActor(String serviceId) {
}
