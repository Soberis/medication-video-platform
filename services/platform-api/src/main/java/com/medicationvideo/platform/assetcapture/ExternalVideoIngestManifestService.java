package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExternalVideoIngestManifestService {

	private final ExternalVideoIngestManifestRepository manifestRepository;

	private final ExternalVideoIngestManifestVideoRepository videoRepository;

	ExternalVideoIngestManifestService(ExternalVideoIngestManifestRepository manifestRepository,
			ExternalVideoIngestManifestVideoRepository videoRepository) {
		this.manifestRepository = manifestRepository;
		this.videoRepository = videoRepository;
	}

	@Transactional
	ExternalVideoIngestManifestAcceptedResponse accept(UUID projectId, ExternalVideoIngestManifestRequest request) {
		var group = request.captureGroup();
		var existing = this.manifestRepository.findByProjectIdAndCaptureGroupKeyAndKeySource(projectId,
				group.captureGroupKey(), group.keySource());
		if (existing.isPresent()) {
			var manifest = existing.get();
			if (!matches(manifest, request)) {
				throw new UploadBatchInputException("EXTERNAL_MANIFEST_CONFLICT",
						"The capture group key already exists with different manifest content.");
			}
			return response(manifest);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var manifest = ExternalVideoIngestManifest.accept(projectId, request, now);
		try {
			this.manifestRepository.saveAndFlush(manifest);
			var videos = request.videos()
				.stream()
				.map(video -> ExternalVideoIngestManifestVideo.accept(manifest.getManifestId(), video))
				.toList();
			this.videoRepository.saveAllAndFlush(videos);
			return response(manifest);
		}
		catch (DataIntegrityViolationException exception) {
			var concurrent = this.manifestRepository
				.findByProjectIdAndCaptureGroupKeyAndKeySource(projectId, group.captureGroupKey(), group.keySource())
				.orElseThrow(() -> exception);
			if (!matches(concurrent, request)) {
				throw new UploadBatchInputException("EXTERNAL_MANIFEST_CONFLICT",
						"The capture group key already exists with different manifest content.");
			}
			return response(concurrent);
		}
	}

	private boolean matches(ExternalVideoIngestManifest manifest, ExternalVideoIngestManifestRequest request) {
		var group = request.captureGroup();
		if (!Objects.equals(manifest.getSchemaVersion(), request.schemaVersion())
				|| !Objects.equals(manifest.getSourceSystem(), request.sourceSystem())
				|| !Objects.equals(manifest.getSourceDeviceId(), request.sourceDeviceId())
				|| !Objects.equals(manifest.getSourceBatchId(), request.sourceBatchId())
				|| !Objects.equals(manifest.getCaptureGroupKey(), group.captureGroupKey())
				|| manifest.getKeySource() != group.keySource()
				|| !Objects.equals(manifest.getCapturedAt(), group.capturedAt())
				|| !Objects.equals(manifest.getTimezone(), group.timezone())
				|| !Objects.equals(manifest.getExpectedCameraNumbers(),
						group.expectedCameraNumbers().stream().sorted().toList())) {
			return false;
		}
		var persisted = this.videoRepository.findAllByManifestIdOrderByCameraNumber(manifest.getManifestId());
		var requested = request.videos()
			.stream()
			.sorted(Comparator.comparing(ExternalVideoManifestVideo::cameraNumber))
			.toList();
		if (persisted.size() != requested.size()) {
			return false;
		}
		for (int index = 0; index < persisted.size(); index++) {
			var actual = persisted.get(index);
			var expected = requested.get(index);
			if (!Objects.equals(actual.getClientFileId(), expected.clientFileId())
					|| actual.getCameraNumber() != expected.cameraNumber()
					|| !Objects.equals(actual.getFileName(), expected.fileName())
					|| actual.getDeclaredSizeBytes() != expected.declaredSizeBytes()
					|| !Objects.equals(actual.getSha256(), expected.sha256())
					|| !Objects.equals(actual.getRecordStartAt(), expected.recordStartAt())) {
				return false;
			}
		}
		return true;
	}

	private ExternalVideoIngestManifestAcceptedResponse response(ExternalVideoIngestManifest manifest) {
		var videoCount = this.videoRepository.findAllByManifestIdOrderByCameraNumber(manifest.getManifestId()).size();
		return new ExternalVideoIngestManifestAcceptedResponse(manifest.getManifestId(), manifest.getStatus().name(),
				manifest.getSchemaVersion(), manifest.getCaptureGroupKey(), manifest.getKeySource().name(),
				manifest.getCapturedAt(), manifest.getExpectedCameraNumbers().size(), videoCount);
	}

}
