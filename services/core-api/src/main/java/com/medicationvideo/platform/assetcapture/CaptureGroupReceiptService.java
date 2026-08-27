package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureGroupReceiptService {

	private final UploadBatchRepository batchRepository;

	private final CaptureSessionRepository sessionRepository;

	private final CameraSetSnapshotRepository snapshotRepository;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final ExternalVideoIngestManifestRepository manifestRepository;

	private final ExternalVideoIngestManifestVideoRepository manifestVideoRepository;

	private final ClipAssetRepository clipAssetRepository;

	CaptureGroupReceiptService(UploadBatchRepository batchRepository, CaptureSessionRepository sessionRepository,
			CameraSetSnapshotRepository snapshotRepository, CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository, ExternalVideoIngestManifestRepository manifestRepository,
			ExternalVideoIngestManifestVideoRepository manifestVideoRepository,
			ClipAssetRepository clipAssetRepository) {
		this.batchRepository = batchRepository;
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.manifestRepository = manifestRepository;
		this.manifestVideoRepository = manifestVideoRepository;
		this.clipAssetRepository = clipAssetRepository;
	}

	@Transactional
	void refreshForFinalizedManifest(ExternalVideoIngestManifest manifest) {
		if (!manifest.isUploadSetFinalized()) {
			return;
		}
		var batch = this.batchRepository.findTopByExternalManifestIdOrderByCreatedAtDesc(manifest.getManifestId())
			.orElse(null);
		if (batch == null) {
			return;
		}
		var session = this.sessionRepository.findForReceiptFinalization(batch.getBatchId(), batch.getProjectId())
			.orElse(null);
		if (session == null) {
			return;
		}
		finalizeSession(manifest, session);
	}

	@Transactional
	void finalizeManifest(ExternalVideoIngestManifest manifest) {
		if (!"external-video-ingest-v2".equals(manifest.getSchemaVersion())) {
			throw new UploadBatchInputException("CAPTURE_GROUP_RECEIPT_NOT_FINALIZABLE",
					"The capture group requires external-video-ingest-v2 for explicit receipt finalization.");
		}
		var batch = this.batchRepository.findTopByExternalManifestIdOrderByCreatedAtDesc(manifest.getManifestId())
			.orElseThrow(() -> new UploadBatchInputException("CAPTURE_GROUP_RECEIPT_NOT_READY",
					"The upload batch must produce a CaptureSession before receipt finalization."));
		var session = this.sessionRepository.findForReceiptFinalization(batch.getBatchId(), batch.getProjectId())
			.orElseThrow(() -> new UploadBatchInputException("CAPTURE_GROUP_RECEIPT_NOT_READY",
					"The upload batch must produce a CaptureSession before receipt finalization."));
		if (!manifest.isUploadSetFinalized()) {
			manifest.markUploadSetFinalized(Instant.now().truncatedTo(ChronoUnit.MICROS));
			this.manifestRepository.saveAndFlush(manifest);
		}
		finalizeSession(manifest, session);
	}

	@Transactional
	void finalizeCaptureGroup(UUID projectId, UUID captureGroupId) {
		var session = this.sessionRepository.findForReceiptFinalizationByCaptureSessionId(captureGroupId, projectId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_GROUP_NOT_FOUND",
					"The capture group was not found in the current project."));
		var manifestId = session.getCaptureGroupManifestId();
		if (manifestId == null) {
			finalizeSession(session, Instant.now().truncatedTo(ChronoUnit.MICROS));
			return;
		}
		var manifest = this.manifestRepository.findForBinding(manifestId, projectId)
			.orElseThrow(() -> new UploadBatchInputException("EXTERNAL_MANIFEST_NOT_FOUND",
					"The capture group receipt manifest was not found in the current project."));
		if (!"external-video-ingest-v2".equals(manifest.getSchemaVersion())) {
			throw new UploadBatchInputException("CAPTURE_GROUP_RECEIPT_NOT_FINALIZABLE",
					"The capture group requires external-video-ingest-v2 for explicit receipt finalization.");
		}
		this.finalizeManifest(manifest);
	}

	ReceiptSnapshot read(ExternalVideoIngestManifest manifest) {
		var expected = manifest.getExpectedCameraNumbers().stream().sorted().toList();
		var received = actualReceivedCameraNumbers(manifest);
		var receivedNumbers = received.stream().sorted().toList();
		var receivedSet = Set.copyOf(receivedNumbers);
		var missing = expected.stream().filter(camera -> !receivedSet.contains(camera)).toList();
		var state = !manifest.isUploadSetFinalized() ? CaptureGroupReceiptState.RECEIVING
				: (missing.isEmpty() ? CaptureGroupReceiptState.COMPLETE : CaptureGroupReceiptState.INCOMPLETE);
		return new ReceiptSnapshot(expected, receivedNumbers, missing, state, receivedNumbers.size());
	}

	private void finalizeSession(ExternalVideoIngestManifest manifest, CaptureSession session) {
		var snapshot = this.snapshotRepository.findByCaptureSessionId(session.getCaptureSessionId())
			.orElseThrow(() -> new IllegalStateException("Capture session has no frozen camera set snapshot."));
		var expected = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId())
			.stream()
			.filter(CameraSetSnapshotMember::isEnabled)
			.map(member -> member.getCameraIndex() + 1)
			.collect(Collectors.toUnmodifiableSet());
		var received = this.streamRepository
			.findAllByCaptureSessionIdOrderByCameraIndexAsc(session.getCaptureSessionId())
			.stream()
			.map(stream -> stream.getCameraIndex() + 1)
			.collect(Collectors.toUnmodifiableSet());
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		session.finalizeReceipt(expected, received, manifest.getUploadSetFinalizedAt(), now);
		this.sessionRepository.saveAndFlush(session);
	}

	private void finalizeSession(CaptureSession session, Instant finalizedAt) {
		var snapshot = this.snapshotRepository.findByCaptureSessionId(session.getCaptureSessionId())
			.orElseThrow(() -> new IllegalStateException("Capture session has no frozen camera set snapshot."));
		var expected = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId())
			.stream()
			.filter(CameraSetSnapshotMember::isEnabled)
			.map(member -> member.getCameraIndex() + 1)
			.collect(Collectors.toUnmodifiableSet());
		var received = this.streamRepository
			.findAllByCaptureSessionIdOrderByCameraIndexAsc(session.getCaptureSessionId())
			.stream()
			.map(stream -> stream.getCameraIndex() + 1)
			.collect(Collectors.toUnmodifiableSet());
		session.finalizeReceipt(expected, received, finalizedAt, finalizedAt);
		this.sessionRepository.saveAndFlush(session);
	}

	private Set<Integer> actualReceivedCameraNumbers(ExternalVideoIngestManifest manifest) {
		var batch = this.batchRepository.findTopByExternalManifestIdOrderByCreatedAtDesc(manifest.getManifestId())
			.orElse(null);
		if (batch == null) {
			return Set.of();
		}
		var session = this.sessionRepository.findByBatchIdAndProjectId(batch.getBatchId(), batch.getProjectId());
		if (session.isPresent()) {
			return this.streamRepository
				.findAllByCaptureSessionIdOrderByCameraIndexAsc(session.get().getCaptureSessionId())
				.stream()
				.map(stream -> stream.getCameraIndex() + 1)
				.collect(Collectors.toUnmodifiableSet());
		}

		Map<UUID, Integer> cameraByClientFileId = this.manifestVideoRepository
			.findAllByManifestIdOrderByCameraNumber(manifest.getManifestId())
			.stream()
			.collect(Collectors.toUnmodifiableMap(ExternalVideoIngestManifestVideo::getClientFileId,
					ExternalVideoIngestManifestVideo::getCameraNumber));
		return this.clipAssetRepository.findAllByBatchIdOrderByClientFileIdAsc(batch.getBatchId())
			.stream()
			.map(asset -> cameraByClientFileId.get(asset.getClientFileId()))
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toUnmodifiableSet());
	}

	record ReceiptSnapshot(List<Integer> expectedCameraNumbers, List<Integer> receivedCameraNumbers,
			List<Integer> missingCameraNumbers, CaptureGroupReceiptState receiptState, int receivedVideoCount) {
	}

}
