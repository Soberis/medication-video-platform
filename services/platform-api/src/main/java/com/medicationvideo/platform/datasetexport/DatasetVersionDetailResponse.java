package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record DatasetVersionDetailResponse(
		UUID datasetVersionId,
		UUID projectId,
		String displayName,
		DatasetVersionStatus status,
		String createdBy,
		Instant createdAt,
		List<DatasetMemberSessionResponse> members) {

	DatasetVersionDetailResponse {
		members = List.copyOf(members);
	}

	static DatasetVersionDetailResponse from(
			DatasetVersion version, List<DatasetMemberSessionResponse> members) {
		return new DatasetVersionDetailResponse(
				version.getDatasetVersionId(),
				version.getProjectId(),
				version.getDisplayName(),
				version.getStatus(),
				version.getCreatedBy(),
				version.getCreatedAt(),
				members);
	}
}

record DatasetMemberSessionResponse(
		UUID captureSessionId,
		int candidateIndex,
		int expectedCameraCount,
		int receivedCameraCount,
		String assemblyStatus,
		String qualityStatus,
		List<DatasetMemberStreamResponse> streams) {

	DatasetMemberSessionResponse {
		streams = List.copyOf(streams);
	}

	static DatasetMemberSessionResponse from(
			DatasetMemberSession session, List<DatasetMemberStreamResponse> streams) {
		return new DatasetMemberSessionResponse(
				session.getCaptureSessionId(),
				session.getCandidateIndex(),
				session.getExpectedCameraCount(),
				session.getReceivedCameraCount(),
				session.getAssemblyStatus(),
				session.getQualityStatus(),
				streams);
	}
}

record DatasetMemberStreamResponse(
		UUID cameraStreamId,
		UUID clipAssetId,
		int cameraIndex,
		String assetSha256,
		String qualityReviewDecision,
		long qualityReviewVersion,
		UUID annotationRevisionId,
		String annotationDocumentSha256,
		String labelSchemaDigest,
		UUID cleaningResultId,
		UUID cleaningRunId,
		String cleaningRuleSetDigest,
		String postCleaningOutcome) {

	static DatasetMemberStreamResponse from(DatasetMemberStream stream) {
		return new DatasetMemberStreamResponse(
				stream.getCameraStreamId(),
				stream.getClipAssetId(),
				stream.getCameraIndex(),
				stream.getAssetSha256(),
				stream.getQualityReviewDecision(),
				stream.getQualityReviewVersion(),
				stream.getAnnotationRevisionId(),
				stream.getAnnotationDocumentSha256(),
				stream.getLabelSchemaDigest(),
				stream.getCleaningResultId(),
				stream.getCleaningRunId(),
				stream.getCleaningRuleSetDigest(),
				stream.getPostCleaningOutcome());
	}
}
