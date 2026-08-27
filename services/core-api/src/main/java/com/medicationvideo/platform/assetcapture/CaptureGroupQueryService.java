package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureGroupQueryService {

	private final CaptureSessionRepository sessionRepository;

	private final CameraSetSnapshotRepository snapshotRepository;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	CaptureGroupQueryService(CaptureSessionRepository sessionRepository, CameraSetSnapshotRepository snapshotRepository,
			CameraSetSnapshotMemberRepository snapshotMemberRepository, CameraStreamRepository streamRepository) {
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
	}

	@Transactional(readOnly = true)
	CaptureSessionResponse get(UUID projectId, UUID captureGroupId) {
		var session = this.sessionRepository.findByCaptureSessionIdAndProjectId(captureGroupId, projectId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_GROUP_NOT_FOUND",
					"The capture group was not found in the current project."));
		var snapshot = this.snapshotRepository.findByCaptureSessionId(session.getCaptureSessionId())
			.orElseThrow(() -> new IllegalStateException("Capture session has no frozen camera set snapshot."));
		var members = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId());
		var streams = this.streamRepository
			.findAllByCaptureSessionIdOrderByCameraIndexAsc(session.getCaptureSessionId());
		return CaptureSessionResponse.from(session, snapshot, members, streams);
	}

}
