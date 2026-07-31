package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlatformLocalCaptureSessionService {

	private static final String LOCAL_LAYOUT_VERSION = "platform-local-v1";

	private final UploadBatchRepository batchRepository;

	private final ClipAssetRepository clipAssetRepository;

	private final CameraChannelRepository channelRepository;

	private final CameraSetConfigurationRepository cameraSetRepository;

	private final CameraSetMemberRepository cameraSetMemberRepository;

	private final CaptureSessionRepository sessionRepository;

	private final CameraSetSnapshotRepository snapshotRepository;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final CaptureSessionMediaProjectionService sessionProjection;

	PlatformLocalCaptureSessionService(UploadBatchRepository batchRepository, ClipAssetRepository clipAssetRepository,
			CameraChannelRepository channelRepository, CameraSetConfigurationRepository cameraSetRepository,
			CameraSetMemberRepository cameraSetMemberRepository, CaptureSessionRepository sessionRepository,
			CameraSetSnapshotRepository snapshotRepository, CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository, CaptureSessionMediaProjectionService sessionProjection) {
		this.batchRepository = batchRepository;
		this.clipAssetRepository = clipAssetRepository;
		this.channelRepository = channelRepository;
		this.cameraSetRepository = cameraSetRepository;
		this.cameraSetMemberRepository = cameraSetMemberRepository;
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.sessionProjection = sessionProjection;
	}

	@Transactional
	CaptureSessionResponse ingest(UUID projectId, UUID batchId) {
		var batch = this.batchRepository.findForCompletion(batchId, projectId)
			.orElseThrow(UploadBatchNotFoundException::new);
		if (batch.getUploadSourceType() != UploadSourceType.PLATFORM_LOCAL) {
			throw new CaptureSessionIngestException("CAPTURE_SESSION_SOURCE_MISMATCH",
					"Platform-local session ingest requires a platform-local upload batch.");
		}
		var existing = this.sessionRepository.findByBatchIdAndProjectId(batchId, projectId);
		if (existing.isPresent()) {
			return response(existing.get());
		}
		if (batch.getStatus() != UploadBatchStatus.COMPLETED) {
			throw new CaptureSessionIngestException("CAPTURE_SESSION_BATCH_NOT_COMPLETED",
					"The upload batch must complete integrity verification before session ingest.");
		}
		var assets = this.clipAssetRepository.findAllByBatchIdOrderByClientFileIdAsc(batchId);
		if (assets.size() != 1) {
			throw new CaptureSessionIngestException("PLATFORM_LOCAL_SINGLE_VIDEO_REQUIRED",
					"A platform-local batch must have exactly one uploaded video asset.");
		}
		if (assets.getFirst().isDeleted()) {
			throw new CaptureSessionIngestException("CAPTURE_SESSION_CLIP_ASSET_UNAVAILABLE",
					"A deleted clip asset cannot be bound to a capture session.");
		}

		var now = Instant.now();
		var channel = CameraChannel.provision(projectId, "platform-local:" + batchId, now);
		this.channelRepository.saveAndFlush(channel);
		var cameraSet = CameraSetConfiguration.create(projectId, "platform-local:" + batchId, 1, LOCAL_LAYOUT_VERSION,
				sha256("platform-local:" + batchId), now);
		this.cameraSetRepository.saveAndFlush(cameraSet);
		var member = CameraSetMember.create(cameraSet.getCameraSetId(),
				new CameraSetMemberRequest(channel.getCameraChannelId(), 0, "PLATFORM_LOCAL", "PLATFORM_LOCAL", true),
				now);
		this.cameraSetMemberRepository.saveAndFlush(member);

		var session = CaptureSession.importLocal(projectId, batch, cameraSet, now);
		this.sessionRepository.saveAndFlush(session);
		var snapshot = CameraSetSnapshot.freeze(session.getCaptureSessionId(), cameraSet, 1, now);
		this.snapshotRepository.saveAndFlush(snapshot);
		var snapshotMember = CameraSetSnapshotMember.freeze(snapshot.getCameraSetSnapshotId(), member, now);
		this.snapshotMemberRepository.saveAndFlush(snapshotMember);
		var stream = CameraStream.importLocal(session.getCaptureSessionId(), snapshotMember, assets.getFirst(), now);
		this.streamRepository.saveAndFlush(stream);
		this.sessionProjection.refresh(projectId, session.getCaptureSessionId(), now);
		return CaptureSessionResponse.from(session, snapshot, java.util.List.of(snapshotMember),
				java.util.List.of(stream));
	}

	private CaptureSessionResponse response(CaptureSession session) {
		var snapshot = this.snapshotRepository.findByCaptureSessionId(session.getCaptureSessionId())
			.orElseThrow(() -> new IllegalStateException("A capture session has no frozen camera set snapshot."));
		var members = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId());
		var streams = this.streamRepository
			.findAllByCaptureSessionIdOrderByCameraIndexAsc(session.getCaptureSessionId());
		return CaptureSessionResponse.from(session, snapshot, members, streams);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
		}
	}

}
