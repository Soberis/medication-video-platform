package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

	CaptureGroupReceiptService(UploadBatchRepository batchRepository, CaptureSessionRepository sessionRepository,
			CameraSetSnapshotRepository snapshotRepository, CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository) {
		this.batchRepository = batchRepository;
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
	}

	@Transactional
	void refreshForFinalizedManifest(ExternalVideoIngestManifest manifest) {
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

}
