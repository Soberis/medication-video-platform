package com.medicationvideo.platform.assetcapture;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExternalVideoIngestManifestService {

	private final ExternalVideoIngestManifestRepository manifestRepository;

	private final ExternalVideoIngestManifestVideoRepository videoRepository;

	private final JdbcTemplate jdbcTemplate;

	ExternalVideoIngestManifestService(ExternalVideoIngestManifestRepository manifestRepository,
			ExternalVideoIngestManifestVideoRepository videoRepository, JdbcTemplate jdbcTemplate) {
		this.manifestRepository = manifestRepository;
		this.videoRepository = videoRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	ExternalVideoIngestManifestAcceptedResponse accept(UUID projectId, ExternalVideoIngestManifestRequest request) {
		request = normalize(request);
		validateTimezone(request.captureGroup().timezone());
		var group = request.captureGroup();
		lockNaturalKey(projectId, group);
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
		this.manifestRepository.saveAndFlush(manifest);
		var videos = request.videos()
			.stream()
			.map(video -> ExternalVideoIngestManifestVideo.accept(manifest.getManifestId(), video))
			.toList();
		this.videoRepository.saveAllAndFlush(videos);
		return response(manifest);
	}

	private static ExternalVideoIngestManifestRequest normalize(ExternalVideoIngestManifestRequest request) {
		var group = request.captureGroup();
		var normalizedGroup = new ExternalVideoCaptureGroup(group.captureGroupKey(), group.keySource(),
				group.capturedAt().truncatedTo(ChronoUnit.MICROS), group.timezone(), group.expectedCameraNumbers());
		var normalizedVideos = request.videos()
			.stream()
			.map(video -> new ExternalVideoManifestVideo(video.clientFileId(), video.cameraNumber(), video.fileName(),
					video.declaredSizeBytes(), video.sha256(),
					video.recordStartAt() == null ? null : video.recordStartAt().truncatedTo(ChronoUnit.MICROS)))
			.toList();
		return new ExternalVideoIngestManifestRequest(request.schemaVersion(), request.sourceSystem(),
				request.sourceDeviceId(), request.sourceBatchId(), normalizedGroup, normalizedVideos);
	}

	private static void validateTimezone(String timezone) {
		try {
			ZoneId.of(timezone);
		}
		catch (DateTimeException exception) {
			throw new UploadBatchInputException("EXTERNAL_MANIFEST_TIMEZONE_INVALID",
					"The external manifest timezone must be a valid ZoneId.");
		}
	}

	private void lockNaturalKey(UUID projectId, ExternalVideoCaptureGroup group) {
		// The natural-key row does not exist on the first request, so a JPA row lock
		// cannot serialize two creators. PostgreSQL's transaction advisory lock does;
		// keeping the whole operation in one transaction means a replay observes only
		// committed manifest and video facts and never queries an aborted transaction.
		this.jdbcTemplate.execute((java.sql.Connection connection) -> {
			try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
				statement.setString(1, projectId + "\n" + group.keySource().name() + "\n" + group.captureGroupKey());
				statement.execute();
				return null;
			}
		});
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
