package com.medicationvideo.platform.assetcapture;

import java.time.Duration;
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
class RawReviewPlaybackService {

	private static final String PURPOSE = "PRIVACY_REVIEW";
	private static final String MP4_CONTENT_TYPE = "video/mp4";

	private final ProjectAccessService projectAccessService;
	private final ClipAssetRepository assetRepository;
	private final MultipartObjectStorage objectStorage;
	private final AuditEventRecorder auditRecorder;
	private final Duration authorizationLifetime;

	RawReviewPlaybackService(
			ProjectAccessService projectAccessService,
			ClipAssetRepository assetRepository,
			MultipartObjectStorage objectStorage,
			AuditEventRecorder auditRecorder,
			@org.springframework.beans.factory.annotation.Value(
					"${platform.playback.raw-review-url-ttl:PT5M}") Duration authorizationLifetime) {
		this.projectAccessService = projectAccessService;
		this.assetRepository = assetRepository;
		this.objectStorage = objectStorage;
		this.auditRecorder = auditRecorder;
		this.authorizationLifetime = authorizationLifetime;
	}

	@Transactional
	RawReviewPlaybackGrantResponse grant(
			UUID projectId,
			UUID clipAssetId,
			String actorId,
			String requestId,
			String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var asset = this.assetRepository.findForUpdate(clipAssetId, projectId)
				.orElseThrow(() -> new RawReviewPlaybackException(
						"CLIP_ASSET_NOT_FOUND",
						"The clip asset was not found in the project.",
						404,
						false));
		if (asset.isDeleted()) {
			throw new RawReviewPlaybackException(
					"CLIP_ASSET_DELETED",
					"The clip asset has been deleted and cannot be reviewed.",
					409,
					false);
		}
		if (asset.getStatus() != ClipAssetStatus.INGESTED) {
			throw new RawReviewPlaybackException(
					"RAW_REVIEW_PLAYBACK_NOT_READY",
					"The clip asset must complete media ingest before raw review playback.",
					409,
					false);
		}
		if (!MP4_CONTENT_TYPE.equalsIgnoreCase(asset.getContentType())) {
			throw new RawReviewPlaybackException(
					"RAW_REVIEW_PLAYBACK_UNSUPPORTED_MEDIA",
					"Raw review playback currently supports video/mp4 only.",
					409,
					false);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		final var mediaUrl = authorize(asset);
		var auditEventId = this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"RAW_REVIEW_PLAYBACK_GRANTED",
				"ClipAsset",
				clipAssetId.toString(),
				Long.toString(asset.getVersion()),
				AuditOutcome.SUCCEEDED,
				PURPOSE,
				requestId,
				traceId,
				"ROLE_ADMIN+PROJECT_MEMBERSHIP_ADMIN",
				Map.of("purpose", PURPOSE, "content_type", asset.getContentType())));
		return new RawReviewPlaybackGrantResponse(
				auditEventId,
				PURPOSE,
				mediaUrl,
				now.plus(this.authorizationLifetime),
				asset.getContentType());
	}

	private java.net.URI authorize(ClipAsset asset) {
		try {
			return this.objectStorage.authorizeRead(asset.getObjectReference(), this.authorizationLifetime);
		}
		catch (MultipartObjectStorageException exception) {
			throw new RawReviewPlaybackException(
					"OBJECT_STORAGE_UNAVAILABLE",
					"Object storage could not authorize raw review playback.",
					503,
					true,
					exception);
		}
	}
}
