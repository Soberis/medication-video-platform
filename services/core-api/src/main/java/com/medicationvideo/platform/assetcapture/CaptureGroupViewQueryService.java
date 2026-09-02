package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public, read-only CaptureGroup boundary for downstream workflow modules.
 *
 * <p>
 * The service deliberately exposes a projection instead of any assetcapture repository or
 * entity. A CaptureGroup is still backed by one CaptureSession; the views are the frozen
 * snapshot members plus any bound CameraStream.
 */
@Service
public class CaptureGroupViewQueryService {

	private final CaptureSessionRepository sessionRepository;

	private final CameraSetSnapshotRepository snapshotRepository;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final ClipAssetRepository clipAssetRepository;

	CaptureGroupViewQueryService(CaptureSessionRepository sessionRepository,
			CameraSetSnapshotRepository snapshotRepository, CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository, ClipAssetRepository clipAssetRepository) {
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.clipAssetRepository = clipAssetRepository;
	}

	@Transactional(readOnly = true)
	public CaptureGroupFacts get(UUID projectId, UUID captureGroupId) {
		var session = this.sessionRepository
			.findByCaptureSessionIdAndProjectIdAndCaptureGroupWorkflowTrue(captureGroupId, projectId)
			.orElseThrow(CaptureGroupViewQueryService::groupNotFound);
		return project(session, false);
	}

	@Transactional(readOnly = true)
	public List<CaptureGroupFacts> findAll(UUID projectId) {
		return this.sessionRepository
			.findAllByProjectIdAndCaptureGroupWorkflowTrueOrderByCreatedAtDescCaptureSessionIdDesc(projectId)
			.stream()
			.map(session -> project(session, false))
			.toList();
	}

	@Transactional
	public CaptureGroupFacts lockAndGet(UUID projectId, UUID captureGroupId) {
		var session = this.sessionRepository.findForGroupView(captureGroupId, projectId)
			.orElseThrow(CaptureGroupViewQueryService::groupNotFound);
		return project(session, true);
	}

	private CaptureGroupFacts project(CaptureSession session, boolean locked) {
		var snapshot = this.snapshotRepository.findByCaptureSessionId(session.getCaptureSessionId())
			.orElseThrow(() -> new IllegalStateException("Capture session has no frozen camera set snapshot."));
		var members = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId())
			.stream()
			.filter(CameraSetSnapshotMember::isEnabled)
			.toList();
		var streamsByMemberId = this.streamRepository
			.findAllByCaptureSessionIdOrderByCameraIndexAsc(session.getCaptureSessionId())
			.stream()
			.collect(Collectors.toUnmodifiableMap(CameraStream::getCameraSetSnapshotMemberId, Function.identity()));
		var assetIds = streamsByMemberId.values().stream().map(CameraStream::getClipAssetId).toList();
		var assetsById = assetIds.isEmpty() ? Map.<UUID, ClipAsset>of()
				: this.clipAssetRepository.findAllByClipAssetIdInAndProjectId(assetIds, session.getProjectId())
					.stream()
					.collect(Collectors.toUnmodifiableMap(ClipAsset::getClipAssetId, Function.identity()));
		var views = members.stream()
			.map(member -> view(member, streamsByMemberId.get(member.getCameraSetSnapshotMemberId()), assetsById))
			.toList();
		return new CaptureGroupFacts(session.getProjectId(), session.getCaptureSessionId(), session.getReceiptState(),
				session.getVersion(), snapshot.getCameraSetSnapshotId(), locked, views);
	}

	private static CaptureGroupView view(CameraSetSnapshotMember member, CameraStream stream,
			Map<UUID, ClipAsset> assetsById) {
		if (stream == null) {
			return new CaptureGroupView(null, null, member.getCameraChannelId(), member.getCameraIndex() + 1,
					member.getCameraRole(), member.getMountPosition(), "MISSING", null, 0, 0, false, "UNKNOWN", null,
					member.isEnabledForHandDetection());
		}
		var asset = assetsById.get(stream.getClipAssetId());
		return new CaptureGroupView(stream.getCameraStreamId(), stream.getClipAssetId(), stream.getCameraChannelId(),
				member.getCameraIndex() + 1, member.getCameraRole(), member.getMountPosition(),
				stream.getStreamStatus().name(), stream.getQualityStatus().name(), stream.getVersion(),
				asset == null ? 0 : asset.getVersion(), asset != null && !asset.isDeleted(),
				stream.getDeclaredSyncStatus().name(), asset == null ? null : asset.getSha256(),
				member.isEnabledForHandDetection());
	}

	private static AssetCatalogQueryException groupNotFound() {
		return new AssetCatalogQueryException("CAPTURE_GROUP_NOT_FOUND",
				"The capture group was not found in the current project.");
	}

	public record CaptureGroupFacts(UUID projectId, UUID captureGroupId, CaptureGroupReceiptState receiptState,
			long captureGroupVersion, UUID cameraSetSnapshotId, boolean locked, List<CaptureGroupView> views) {

		public CaptureGroupFacts {
			views = List.copyOf(views);
		}
	}

	public record CaptureGroupView(UUID cameraStreamId, UUID clipAssetId, UUID cameraChannelId, int cameraNumber,
			String cameraRole, String mountPosition, String mediaStatus, String technicalQualityStatus,
			long streamVersion, long clipAssetVersion, boolean assetAvailable, String declaredSyncStatus,
			String clipAssetSha256, boolean enabledForHandDetection) {

		public CaptureGroupView(UUID cameraStreamId, UUID clipAssetId, UUID cameraChannelId, int cameraNumber,
				String cameraRole, String mountPosition, String mediaStatus, String technicalQualityStatus,
				long streamVersion, long clipAssetVersion, boolean assetAvailable, String declaredSyncStatus,
				String clipAssetSha256) {
			this(cameraStreamId, clipAssetId, cameraChannelId, cameraNumber, cameraRole, mountPosition, mediaStatus,
					technicalQualityStatus, streamVersion, clipAssetVersion, assetAvailable, declaredSyncStatus,
					clipAssetSha256, true);
		}

		public CaptureGroupView(UUID cameraStreamId, UUID clipAssetId, UUID cameraChannelId, int cameraNumber,
				String cameraRole, String mountPosition, String mediaStatus, String technicalQualityStatus,
				long streamVersion, long clipAssetVersion, boolean assetAvailable, String declaredSyncStatus) {
			this(cameraStreamId, clipAssetId, cameraChannelId, cameraNumber, cameraRole, mountPosition, mediaStatus,
					technicalQualityStatus, streamVersion, clipAssetVersion, assetAvailable, declaredSyncStatus, null,
					true);
		}

		public CaptureGroupView(UUID cameraStreamId, UUID clipAssetId, UUID cameraChannelId, int cameraNumber,
				String cameraRole, String mountPosition, String mediaStatus, String technicalQualityStatus,
				long streamVersion, long clipAssetVersion, boolean assetAvailable) {
			this(cameraStreamId, clipAssetId, cameraChannelId, cameraNumber, cameraRole, mountPosition, mediaStatus,
					technicalQualityStatus, streamVersion, clipAssetVersion, assetAvailable, "UNKNOWN", null, true);
		}

		public boolean present() {
			return this.cameraStreamId != null && this.clipAssetId != null;
		}
	}

}
