package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

record UploadBatchCompletionResponse(
		UUID batchId,
		UUID projectId,
		UploadBatchStatus status,
		int fileCount,
		long totalSizeBytes,
		String manifestSha256,
		String verifiedManifestSha256,
		int clipAssetCount,
		List<ClipAssetBindingResponse> clipAssets,
		Instant completedAt) {

	static UploadBatchCompletionResponse from(UploadBatch batch, List<ClipAsset> assets) {
		if (batch.getStatus() != UploadBatchStatus.COMPLETED) {
			throw new IllegalArgumentException("Only a completed batch has a completion response.");
		}
		var bindings = assets.stream()
				.sorted(Comparator.comparing(asset -> asset.getClientFileId().toString()))
				.map(ClipAssetBindingResponse::from)
				.toList();
		return new UploadBatchCompletionResponse(
				batch.getBatchId(),
				batch.getProjectId(),
				batch.getStatus(),
				batch.getFileCount(),
				batch.getTotalSizeBytes(),
				batch.getManifestSha256(),
				batch.getVerifiedManifestSha256(),
				bindings.size(),
				bindings,
				batch.getCompletedAt());
	}
}
