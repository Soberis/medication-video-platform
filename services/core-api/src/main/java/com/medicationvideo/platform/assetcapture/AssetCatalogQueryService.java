package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetCatalogQueryService {

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final ClipAssetRepository clipAssetRepository;

	public AssetCatalogQueryService(CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository, ClipAssetRepository clipAssetRepository) {
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.clipAssetRepository = clipAssetRepository;
	}

	@Transactional(readOnly = true)
	ClipAssetReadResponse getClipAsset(UUID projectId, UUID clipAssetId) {
		var asset = this.clipAssetRepository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
			.filter(existing -> !existing.isDeleted())
			.orElseThrow(() -> new AssetCatalogQueryException("CLIP_ASSET_NOT_FOUND",
					"The clip asset was not found in the current project."));
		var binding = this.streamRepository.findByClipAssetId(clipAssetId).map(stream -> {
			var member = this.snapshotMemberRepository.findById(stream.getCameraSetSnapshotMemberId())
				.orElseThrow(() -> new IllegalStateException("A camera stream has no matching snapshot member."));
			return binding(stream, member);
		}).orElse(null);
		return new ClipAssetReadResponse(asset.getClipAssetId(), asset.getBatchId(), asset.getSourceUploadFileId(),
				asset.getClientFileId(), asset.getContentType(), asset.getSizeBytes(), asset.getSha256(),
				asset.getStatus(), binding, asset.getCreatedAt(), asset.getUpdatedAt());
	}

	/**
	 * Cross-module read-only entry point. Returns a frozen snapshot of the fields a
	 * downstream module (e.g. mediaprivacy) needs to schedule work against a clip asset.
	 * Does not leak storage locations, ETag or batch identifiers.
	 */
	@Transactional(readOnly = true)
	public ClipAssetInputSummary getClipAssetInputSummary(UUID projectId, UUID clipAssetId) {
		var asset = this.clipAssetRepository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
			.filter(existing -> !existing.isDeleted())
			.orElseThrow(() -> new AssetCatalogQueryException("CLIP_ASSET_NOT_FOUND",
					"The clip asset was not found in the current project."));
		var binding = this.streamRepository.findByClipAssetId(clipAssetId)
			.map(stream -> this.snapshotMemberRepository.findById(stream.getCameraSetSnapshotMemberId())
				.orElseThrow(() -> new IllegalStateException("A camera stream has no matching snapshot member.")));
		var cameraRole = binding.map(CameraSetSnapshotMember::getCameraRole).orElse(null);
		var enabledForHandDetection = binding.map(CameraSetSnapshotMember::isEnabledForHandDetection).orElse(false);
		return new ClipAssetInputSummary(asset.getClipAssetId(), asset.getProjectId(), asset.getContentType(),
				asset.getSizeBytes(), asset.getSha256(), asset.getStatus(), cameraRole, enabledForHandDetection,
				asset.getCreatedAt(), asset.getUpdatedAt());
	}

	private static ClipAssetSessionBindingResponse binding(CameraStream stream, CameraSetSnapshotMember member) {
		return new ClipAssetSessionBindingResponse(stream.getCaptureSessionId(), stream.getCameraStreamId(),
				stream.getCameraChannelId(), stream.getCameraIndex(), stream.getCameraIndex() + 1,
				member.getCameraRole(), member.getMountPosition(), stream.getRecordStartUtc(), stream.getTimeOffsetMs(),
				stream.getDeclaredSyncStatus(), stream.getStreamStatus(), stream.getQualityStatus(),
				member.isEnabledForHandDetection());
	}

}
