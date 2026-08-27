package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UploadBatchCompletionService {

	private final UploadBatchRepository batchRepository;

	private final UploadFileRepository fileRepository;

	private final ClipAssetRepository clipAssetRepository;

	private final ExternalVideoIngestManifestVideoRepository externalManifestVideoRepository;

	private final ExternalVideoIngestManifestRepository externalManifestRepository;

	private final MediaProbeSchedulingPort mediaProbeSchedulingPort;

	UploadBatchCompletionService(UploadBatchRepository batchRepository, UploadFileRepository fileRepository,
			ClipAssetRepository clipAssetRepository, MediaProbeSchedulingPort mediaProbeSchedulingPort,
			ExternalVideoIngestManifestVideoRepository externalManifestVideoRepository,
			ExternalVideoIngestManifestRepository externalManifestRepository) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.clipAssetRepository = clipAssetRepository;
		this.externalManifestVideoRepository = externalManifestVideoRepository;
		this.externalManifestRepository = externalManifestRepository;
		this.mediaProbeSchedulingPort = mediaProbeSchedulingPort;
	}

	@Transactional
	UploadBatchCompletionResponse complete(UUID projectId, UUID batchId) {
		var batch = this.batchRepository.findForCompletion(batchId, projectId)
			.orElseThrow(UploadBatchNotFoundException::new);
		if (batch.getStatus() == UploadBatchStatus.COMPLETED) {
			return response(batch);
		}
		if (batch.getStatus() != UploadBatchStatus.UPLOADING) {
			throw failure("UPLOAD_BATCH_NOT_COMPLETABLE", "The upload batch is not in a completable state.");
		}

		var files = this.fileRepository.findAllByBatchIdOrderByCreatedAtAscUploadFileIdAsc(batchId);
		requireCompleteDeclaration(batch, files);
		requireUploadedFiles(files);
		var verifiedManifestSha256 = UploadBatchManifest.sha256(files);
		if (!batch.getManifestSha256().equals(verifiedManifestSha256)) {
			throw failure("UPLOAD_BATCH_MANIFEST_CHECKSUM_MISMATCH",
					"The registered file manifest does not match the batch declaration.");
		}
		requireExternalManifestVideos(batch, files);

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var assets = files.stream()
			.filter(file -> file.getFileKind() == UploadFileKind.VIDEO)
			.map(file -> ClipAsset.fromUploadedVideo(batch, file, now))
			.toList();
		if (assets.isEmpty()) {
			throw failure("UPLOAD_BATCH_VIDEO_REQUIRED", "The upload batch contains no video file.");
		}
		this.clipAssetRepository.saveAllAndFlush(assets);
		for (var asset : assets) {
			this.mediaProbeSchedulingPort.ensureForUploadedClip(projectId, asset.getClipAssetId());
		}
		batch.markCompleted(verifiedManifestSha256, now);
		this.batchRepository.saveAndFlush(batch);
		return UploadBatchCompletionResponse.from(batch, assets);
	}

	private UploadBatchCompletionResponse response(UploadBatch batch) {
		var assets = this.clipAssetRepository.findAllByBatchIdOrderByClientFileIdAsc(batch.getBatchId());
		if (assets.isEmpty()) {
			throw new IllegalStateException("A completed video batch has no clip asset binding.");
		}
		return UploadBatchCompletionResponse.from(batch, assets);
	}

	private static void requireCompleteDeclaration(UploadBatch batch, List<UploadFile> files) {
		var registeredSize = files.stream().mapToLong(UploadFile::getSizeBytes).sum();
		if (files.size() != batch.getFileCount() || registeredSize != batch.getTotalSizeBytes()) {
			throw failure("UPLOAD_BATCH_MANIFEST_INCOMPLETE",
					"Every declared file and byte must be registered before batch completion.");
		}
	}

	private static void requireUploadedFiles(List<UploadFile> files) {
		if (files.stream().anyMatch(file -> file.getStatus() == UploadFileStatus.FAILED)) {
			throw failure("UPLOAD_BATCH_FILE_FAILED",
					"A file failed final integrity verification and prevents batch completion.");
		}
		if (files.stream().anyMatch(file -> file.getStatus() != UploadFileStatus.UPLOADED)) {
			throw failure("UPLOAD_BATCH_FILES_INCOMPLETE",
					"Every declared file must complete final integrity verification first.");
		}
		if (files.stream().anyMatch(file -> !file.getSha256().equals(file.getVerifiedSha256()))) {
			throw new IllegalStateException("An uploaded file has inconsistent verified integrity state.");
		}
	}

	private void requireExternalManifestVideos(UploadBatch batch, List<UploadFile> files) {
		if (batch.getExternalManifestId() == null) {
			return;
		}
		var manifest = this.externalManifestRepository.findById(batch.getExternalManifestId())
			.filter(candidate -> candidate.getProjectId().equals(batch.getProjectId()))
			.orElseThrow(() -> failure("EXTERNAL_MANIFEST_NOT_FOUND",
					"The upload batch references an external manifest outside the current project."));
		if (manifest.getStatus() != ExternalVideoIngestManifestStatus.BOUND) {
			throw failure("EXTERNAL_MANIFEST_NOT_BOUND",
					"The upload batch references an external manifest that is not bound.");
		}
		var expected = this.externalManifestVideoRepository
			.findAllByManifestIdOrderByCameraNumber(batch.getExternalManifestId());
		var expectedByClientFileId = expected.stream()
			.collect(java.util.stream.Collectors.toUnmodifiableMap(ExternalVideoIngestManifestVideo::getClientFileId,
					java.util.function.Function.identity()));
		var videos = files.stream().filter(file -> file.getFileKind() == UploadFileKind.VIDEO).toList();
		var actualClientFileIds = videos.stream()
			.map(UploadFile::getClientFileId)
			.collect(java.util.stream.Collectors.toSet());
		var manifestAllowsMissingDeclarations = "external-video-ingest-v2".equals(manifest.getSchemaVersion());
		var videoSetMismatch = manifestAllowsMissingDeclarations
				? !expectedByClientFileId.keySet().containsAll(actualClientFileIds)
				: !expectedByClientFileId.keySet().equals(actualClientFileIds);
		if (videoSetMismatch || videos.stream()
			.anyMatch(file -> !matches(expectedByClientFileId.get(file.getClientFileId()), file))) {
			throw failure("EXTERNAL_MANIFEST_VIDEO_SET_MISMATCH",
					"Every registered external-manifest video must match its declared file facts; V2 may retain declared cameras that have not arrived yet.");
		}
	}

	private static boolean matches(ExternalVideoIngestManifestVideo expected, UploadFile actual) {
		return expected != null && expected.getDeclaredSizeBytes() == actual.getSizeBytes()
				&& expected.getSha256().equals(actual.getSha256())
				&& expected.getFileName().equals(actual.getFileName());
	}

	private static UploadBatchCompletionException failure(String errorCode, String message) {
		return new UploadBatchCompletionException(errorCode, message);
	}

}
