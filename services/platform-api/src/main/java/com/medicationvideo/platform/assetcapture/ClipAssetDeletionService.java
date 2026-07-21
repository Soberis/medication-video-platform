package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class ClipAssetDeletionService {

	private final ProjectAccessService projectAccessService;
	private final ClipAssetRepository assetRepository;
	private final CameraStreamRepository streamRepository;
	private final CaptureSessionRepository sessionRepository;
	private final AuditEventRecorder auditRecorder;

	ClipAssetDeletionService(
			ProjectAccessService projectAccessService,
			ClipAssetRepository assetRepository,
			CameraStreamRepository streamRepository,
			CaptureSessionRepository sessionRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.assetRepository = assetRepository;
		this.streamRepository = streamRepository;
		this.sessionRepository = sessionRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	void delete(
			UUID projectId,
			UUID clipAssetId,
			String actorId,
			String requestId,
			String traceId,
			ClipAssetDeletionRequest request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var asset = this.assetRepository.findForUpdate(clipAssetId, projectId)
				.orElseThrow(() -> new ClipAssetDeletionException(
						"CLIP_ASSET_NOT_FOUND",
						"The clip asset was not found in the project.",
						404));
		if (asset.isDeleted()) {
			return;
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		asset.markDeleted(actorId, request.reason().trim(), now);
		asset = this.assetRepository.saveAndFlush(asset);
		this.streamRepository.findByClipAssetId(clipAssetId).ifPresent(stream -> {
			stream.markAssetUnavailable(now);
			this.sessionRepository.findById(stream.getCaptureSessionId())
					.ifPresent(session -> session.markStreamUnavailable(now));
		});
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"CLIP_ASSET_DELETED",
				"ClipAsset",
				clipAssetId.toString(),
				Long.toString(asset.getVersion()),
				AuditOutcome.SUCCEEDED,
				"USER_REQUESTED_DELETION",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of("reason", request.reason().trim())));
	}
}
