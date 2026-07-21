package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Service
class CaptureSessionIngestService {

	private static final int MAX_METADATA_BYTES = 1_048_576;

	private final UploadBatchRepository batchRepository;
	private final UploadFileRepository fileRepository;
	private final ClipAssetRepository clipAssetRepository;
	private final CameraSetConfigurationRepository cameraSetRepository;
	private final CameraSetMemberRepository cameraSetMemberRepository;
	private final CaptureSessionRepository sessionRepository;
	private final CameraSetSnapshotRepository snapshotRepository;
	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;
	private final CameraStreamRepository streamRepository;
	private final MultipartObjectStorage objectStorage;
	private final ObjectMapper objectMapper;
	private final Validator validator;
	private final TransactionTemplate transactionTemplate;

	CaptureSessionIngestService(
			UploadBatchRepository batchRepository,
			UploadFileRepository fileRepository,
			ClipAssetRepository clipAssetRepository,
			CameraSetConfigurationRepository cameraSetRepository,
			CameraSetMemberRepository cameraSetMemberRepository,
			CaptureSessionRepository sessionRepository,
			CameraSetSnapshotRepository snapshotRepository,
			CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository,
			MultipartObjectStorage objectStorage,
			ObjectMapper objectMapper,
			Validator validator,
			TransactionTemplate transactionTemplate) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.clipAssetRepository = clipAssetRepository;
		this.cameraSetRepository = cameraSetRepository;
		this.cameraSetMemberRepository = cameraSetMemberRepository;
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.objectStorage = objectStorage;
		this.objectMapper = objectMapper;
		this.validator = validator;
		this.transactionTemplate = transactionTemplate;
	}

	CaptureSessionResponse ingest(UUID projectId, UUID batchId) {
		var existing = this.sessionRepository.findByBatchIdAndProjectId(batchId, projectId);
		if (existing.isPresent()) {
			return response(existing.get());
		}

		var source = loadMetadataSource(projectId, batchId);
		byte[] bytes;
		try {
			bytes = this.objectStorage.readCompletedObject(source.object(), MAX_METADATA_BYTES);
		}
		catch (MultipartObjectStorageException exception) {
			throw new UploadStorageUnavailableException(exception);
		}
		if (bytes.length != source.sizeBytes()) {
			throw failure(
					"CAPTURE_SESSION_METADATA_SIZE_MISMATCH",
					"The uploaded metadata object size no longer matches its verified manifest.");
		}
		var actualSha256 = sha256(bytes);
		if (!MessageDigest.isEqual(
				source.sha256().getBytes(StandardCharsets.US_ASCII),
				actualSha256.getBytes(StandardCharsets.US_ASCII))) {
			throw failure(
					"CAPTURE_SESSION_METADATA_CHECKSUM_MISMATCH",
					"The uploaded metadata object no longer matches its verified SHA-256.");
		}
		var metadata = parseMetadata(bytes);

		try {
			var created = this.transactionTemplate.execute(status -> create(projectId, batchId, source, metadata));
			if (created == null) {
				throw new IllegalStateException("Capture session transaction returned no result.");
			}
			return created;
		}
		catch (DataIntegrityViolationException exception) {
			var concurrent = this.sessionRepository.findByBatchIdAndProjectId(batchId, projectId);
			if (concurrent.isPresent()
					&& concurrent.get().getCaptureSessionId().equals(metadata.captureSessionId())
					&& concurrent.get().getMetadataSha256().equals(source.sha256())) {
				return response(concurrent.get());
			}
			throw failure(
					"CAPTURE_SESSION_ID_CONFLICT",
					"The capture session or one of its assets is already bound to another source.");
		}
	}

	private MetadataSource loadMetadataSource(UUID projectId, UUID batchId) {
		var batch = this.batchRepository.findByBatchIdAndProjectId(batchId, projectId)
				.orElseThrow(UploadBatchNotFoundException::new);
		if (batch.getStatus() != UploadBatchStatus.COMPLETED) {
			throw failure(
					"CAPTURE_SESSION_BATCH_NOT_COMPLETED",
					"The upload batch must complete integrity verification before session ingest.");
		}
		var metadataFiles = this.fileRepository.findAllByBatchIdOrderByCreatedAtAscUploadFileIdAsc(batchId).stream()
				.filter(file -> file.getFileKind() == UploadFileKind.METADATA)
				.toList();
		if (metadataFiles.size() != 1) {
			throw failure(
					"CAPTURE_SESSION_METADATA_REQUIRED",
					"A completed capture batch must contain exactly one metadata file.");
		}
		var metadataFile = metadataFiles.getFirst();
		if (metadataFile.getStatus() != UploadFileStatus.UPLOADED) {
			throw new IllegalStateException("A completed batch contains metadata that is not uploaded.");
		}
		if (!"application/json".equals(metadataFile.getContentType())) {
			throw failure(
					"CAPTURE_SESSION_METADATA_CONTENT_TYPE_UNSUPPORTED",
					"Capture session metadata must use application/json.");
		}
		if (metadataFile.getSizeBytes() > MAX_METADATA_BYTES) {
			throw failure(
					"CAPTURE_SESSION_METADATA_TOO_LARGE",
					"Capture session metadata exceeds the 1 MiB safety limit.");
		}
		return new MetadataSource(
				metadataFile.getUploadFileId(),
				metadataFile.getSizeBytes(),
				metadataFile.getVerifiedSha256(),
				metadataFile.getStorageObject());
	}

	private CaptureSessionMetadataV1 parseMetadata(byte[] bytes) {
		CaptureSessionMetadataV1 metadata;
		try {
			metadata = this.objectMapper.readerFor(CaptureSessionMetadataV1.class)
					.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
					.readValue(bytes);
		}
		catch (JacksonException exception) {
			throw failure(
					"CAPTURE_SESSION_METADATA_INVALID",
					"Capture session metadata is not valid capture-session-metadata-v1 JSON.");
		}
		if (metadata == null || !this.validator.validate(metadata).isEmpty()) {
			throw failure(
					"CAPTURE_SESSION_METADATA_INVALID",
					"Capture session metadata is missing required fields or contains invalid values.");
		}
		try {
			ZoneId.of(metadata.deviceTimezone());
		}
		catch (DateTimeException exception) {
			throw failure(
					"CAPTURE_SESSION_METADATA_INVALID",
					"Capture session metadata contains an invalid device timezone.");
		}
		return normalize(metadata);
	}

	private CaptureSessionResponse create(
			UUID projectId,
			UUID batchId,
			MetadataSource source,
			CaptureSessionMetadataV1 metadata) {
		var existingByBatch = this.sessionRepository.findByBatchIdAndProjectId(batchId, projectId);
		if (existingByBatch.isPresent()) {
			return requireSameSession(existingByBatch.get(), source, metadata);
		}
		if (this.sessionRepository.existsById(metadata.captureSessionId())) {
			throw failure(
					"CAPTURE_SESSION_ID_CONFLICT",
					"The capture session identifier is already bound to another batch.");
		}

		var batch = this.batchRepository.findByBatchIdAndProjectId(batchId, projectId)
				.orElseThrow(UploadBatchNotFoundException::new);
		if (batch.getStatus() != UploadBatchStatus.COMPLETED) {
			throw failure(
					"CAPTURE_SESSION_BATCH_NOT_COMPLETED",
					"The upload batch must remain completed during session ingest.");
		}
		if (!batch.getDeviceId().equals(metadata.deviceId())) {
			throw failure(
					"CAPTURE_SESSION_DEVICE_MISMATCH",
					"Metadata device_id does not match the completed upload batch.");
		}
		var metadataFile = this.fileRepository.findById(source.uploadFileId())
				.orElseThrow(() -> new IllegalStateException("The verified metadata file no longer exists."));
		if (!metadataFile.getBatchId().equals(batchId)
				|| metadataFile.getFileKind() != UploadFileKind.METADATA
				|| metadataFile.getStatus() != UploadFileStatus.UPLOADED
				|| !metadataFile.getVerifiedSha256().equals(source.sha256())) {
			throw new IllegalStateException("The verified metadata source changed during session ingest.");
		}

		var cameraSet = this.cameraSetRepository.findByCameraSetIdAndProjectId(metadata.cameraSetId(), projectId)
				.orElseThrow(() -> failure(
						"CAPTURE_SESSION_CAMERA_SET_NOT_FOUND",
						"The referenced camera set was not found in the authenticated project."));
		var configuredMembers = this.cameraSetMemberRepository
				.findAllByCameraSetIdOrderByCameraIndexAsc(cameraSet.getCameraSetId());
		var expectedCameraCount = Math.toIntExact(configuredMembers.stream()
				.filter(CameraSetMember::isEnabled)
				.count());
		if (metadata.expectedCameraCount() != expectedCameraCount
				|| !metadata.cameraLayoutVersion().equals(cameraSet.getLayoutVersion())) {
			throw failure(
					"CAPTURE_SESSION_CAMERA_SET_MISMATCH",
					"Edge camera count or layout version does not match the immutable server configuration.");
		}

		var assets = this.clipAssetRepository.findAllByBatchIdOrderByClientFileIdAsc(batchId);
		if (assets.stream().anyMatch(ClipAsset::isDeleted)) {
			throw failure(
					"CAPTURE_SESSION_CLIP_ASSET_UNAVAILABLE",
					"A deleted clip asset cannot be bound to a capture session.");
		}
		var assetsByClientId = assets.stream().collect(Collectors.toUnmodifiableMap(
				ClipAsset::getClientFileId,
				Function.identity()));
		var configuredByChannel = configuredMembers.stream().collect(Collectors.toUnmodifiableMap(
				CameraSetMember::getCameraChannelId,
				Function.identity()));
		validateStreamBindings(metadata.streams(), assetsByClientId, configuredByChannel, projectId);

		var receivedCameraCount = Math.toIntExact(metadata.streams().stream()
				.map(stream -> assetsByClientId.get(stream.clientFileId()))
				.filter(asset -> asset.getStatus() == ClipAssetStatus.INGESTED)
				.count());
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var session = CaptureSession.assemble(
				projectId,
				batchId,
				metadataFile,
				metadata,
				receivedCameraCount,
				now);
		this.sessionRepository.saveAndFlush(session);

		var snapshot = CameraSetSnapshot.freeze(
				session.getCaptureSessionId(),
				cameraSet,
				expectedCameraCount,
				now);
		this.snapshotRepository.saveAndFlush(snapshot);
		var snapshotMembers = configuredMembers.stream()
				.map(member -> CameraSetSnapshotMember.freeze(
						snapshot.getCameraSetSnapshotId(),
						member,
						now))
				.toList();
		this.snapshotMemberRepository.saveAllAndFlush(snapshotMembers);
		Map<UUID, CameraSetSnapshotMember> snapshotByChannel = snapshotMembers.stream()
				.collect(Collectors.toUnmodifiableMap(
						CameraSetSnapshotMember::getCameraChannelId,
						Function.identity()));

		var streams = metadata.streams().stream()
				.sorted(java.util.Comparator.comparingInt(CaptureSessionMetadataV1.Stream::cameraIndex))
				.map(stream -> CameraStream.bind(
						session.getCaptureSessionId(),
						snapshotByChannel.get(stream.cameraChannelId()),
						assetsByClientId.get(stream.clientFileId()),
						stream,
						now))
				.toList();
		this.streamRepository.saveAllAndFlush(streams);
		return CaptureSessionResponse.from(session, snapshot, snapshotMembers, streams);
	}

	private static void validateStreamBindings(
			List<CaptureSessionMetadataV1.Stream> streams,
			Map<UUID, ClipAsset> assetsByClientId,
			Map<UUID, CameraSetMember> configuredByChannel,
			UUID projectId) {
		var channelIds = new HashSet<UUID>();
		var cameraIndexes = new HashSet<Integer>();
		var clientFileIds = new HashSet<UUID>();
		for (var stream : streams) {
			if (!channelIds.add(stream.cameraChannelId())
					|| !cameraIndexes.add(stream.cameraIndex())
					|| !clientFileIds.add(stream.clientFileId())) {
				throw failure(
						"CAPTURE_SESSION_STREAM_DUPLICATE",
						"Camera channels, indices and video file bindings must be unique within a session.");
			}
			var configured = configuredByChannel.get(stream.cameraChannelId());
			if (configured == null
					|| !configured.isEnabled()
					|| !stream.enabled()
					|| configured.getCameraIndex() != stream.cameraIndex()) {
				throw failure(
						"CAPTURE_SESSION_CAMERA_SET_MISMATCH",
						"A stream does not match an enabled member of the immutable server camera set.");
			}
			var asset = assetsByClientId.get(stream.clientFileId());
			if (asset == null || !asset.getProjectId().equals(projectId)) {
				throw failure(
						"CAPTURE_SESSION_VIDEO_BINDING_MISMATCH",
						"Every stream must bind a video asset from the same completed batch and project.");
			}
		}
		if (clientFileIds.size() != assetsByClientId.size()) {
			throw failure(
					"CAPTURE_SESSION_VIDEO_BINDING_MISMATCH",
					"Every video asset in the completed batch must be bound exactly once.");
		}
	}

	private CaptureSessionResponse requireSameSession(
			CaptureSession existing,
			MetadataSource source,
			CaptureSessionMetadataV1 metadata) {
		if (!existing.getCaptureSessionId().equals(metadata.captureSessionId())
				|| !existing.getMetadataSha256().equals(source.sha256())) {
			throw failure(
					"CAPTURE_SESSION_ID_CONFLICT",
					"The upload batch is already bound to a different capture session manifest.");
		}
		return response(existing);
	}

	private CaptureSessionResponse response(CaptureSession session) {
		var snapshot = this.snapshotRepository.findByCaptureSessionId(session.getCaptureSessionId())
				.orElseThrow(() -> new IllegalStateException("Capture session has no frozen camera set snapshot."));
		var members = this.snapshotMemberRepository.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(
				snapshot.getCameraSetSnapshotId());
		var streams = this.streamRepository.findAllByCaptureSessionIdOrderByCameraIndexAsc(
				session.getCaptureSessionId());
		return CaptureSessionResponse.from(session, snapshot, members, streams);
	}

	private static CaptureSessionMetadataV1 normalize(CaptureSessionMetadataV1 metadata) {
		var streams = metadata.streams().stream()
				.map(stream -> new CaptureSessionMetadataV1.Stream(
						stream.clientFileId(),
						stream.cameraChannelId(),
						stream.cameraIndex(),
						stream.enabled(),
						stream.recordStartUtc().truncatedTo(ChronoUnit.MICROS),
						stream.timeOffsetMs(),
						stream.syncStatus()))
				.toList();
		return new CaptureSessionMetadataV1(
				metadata.schemaVersion(),
				metadata.captureSessionId(),
				metadata.deviceId(),
				metadata.cameraSetId(),
				metadata.expectedCameraCount(),
				metadata.cameraLayoutVersion(),
				metadata.syncGroupId(),
				metadata.deviceTimeUtc().truncatedTo(ChronoUnit.MICROS),
				metadata.deviceTimezone(),
				streams);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in the Java runtime.", exception);
		}
	}

	private static CaptureSessionIngestException failure(String errorCode, String message) {
		return new CaptureSessionIngestException(errorCode, message);
	}

	private record MetadataSource(
			UUID uploadFileId,
			long sizeBytes,
			String sha256,
			MultipartObjectStorage.ObjectReference object) {
	}
}
