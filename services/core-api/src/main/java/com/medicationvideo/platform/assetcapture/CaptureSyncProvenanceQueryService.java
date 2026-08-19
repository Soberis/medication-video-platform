package com.medicationvideo.platform.assetcapture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureSyncProvenanceQueryService {

	private final CaptureSessionRepository sessionRepository;

	private final CaptureSyncRevisionRepository revisionRepository;

	private final CaptureSyncStreamRevisionRepository streamRevisionRepository;

	private final CaptureSyncArtifactRepository artifactRepository;

	private final SynchronizedFrameQueryPort frameQueryPort;

	CaptureSyncProvenanceQueryService(CaptureSessionRepository sessionRepository,
			CaptureSyncRevisionRepository revisionRepository,
			CaptureSyncStreamRevisionRepository streamRevisionRepository,
			CaptureSyncArtifactRepository artifactRepository, SynchronizedFrameQueryPort frameQueryPort) {
		this.sessionRepository = sessionRepository;
		this.revisionRepository = revisionRepository;
		this.streamRevisionRepository = streamRevisionRepository;
		this.artifactRepository = artifactRepository;
		this.frameQueryPort = frameQueryPort;
	}

	@Transactional(readOnly = true)
	CaptureSyncProvenanceResponse resolve(UUID projectId, UUID captureSessionId, UUID syncRevisionId,
			long syncFrameIndex, int cameraNumber) {
		requireSession(projectId, captureSessionId);
		var revision = this.revisionRepository.findById(syncRevisionId)
			.filter(candidate -> candidate.getProjectId().equals(projectId)
					&& candidate.getCaptureSessionId().equals(captureSessionId))
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_REVISION_NOT_FOUND",
					"The requested synchronization revision was not found."));
		var streamRevision = this.streamRevisionRepository
			.findAllByCaptureSyncRevisionIdOrderByCameraNumberAsc(syncRevisionId)
			.stream()
			.filter(stream -> stream.getCameraNumber() == cameraNumber)
			.findFirst()
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_STREAM_REVISION_NOT_FOUND",
					"The requested camera stream revision was not found."));
		var artifact = this.artifactRepository
			.findByCaptureSyncRevisionIdAndArtifactRole(syncRevisionId, CaptureSyncArtifact.ARTIFACT_ROLE)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_ARTIFACT_NOT_FOUND",
					"The synchronized-frame artifact was not found."));
		var page = this.frameQueryPort.query(artifact, new SynchronizedFrameQueryPort.FrameQueryRequest(
				captureSessionId, syncRevisionId, syncFrameIndex, List.of(cameraNumber), null, 1));
		var slot = page.items()
			.stream()
			.filter(item -> item.syncFrameIndex() == syncFrameIndex && item.cameraNumber() == cameraNumber)
			.findFirst()
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_FRAME_NOT_FOUND",
					"The requested synchronized frame slot was not found."));
		if ("PRESENT".equals(slot.frameState()) && (slot.cameraStreamId() == null || slot.videoId() == null
				|| slot.sourceFrameIndex() == null || slot.sourcePtsNs() == null || slot.sourcePtsOrdinal() == null
				|| slot.syncErrorNs() == null)) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_PROVENANCE_INVALID",
					"The synchronized frame slot is missing required source provenance.");
		}
		if ("PRESENT".equals(slot.frameState()) && (!slot.cameraStreamId().equals(streamRevision.getCameraStreamId())
				|| !slot.videoId().equals(streamRevision.getClipAssetId()))) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_PROVENANCE_INVALID",
					"The synchronized frame slot source identity does not match the frozen camera stream.");
		}
		var mp4Sha256 = provenanceMp4Sha256(artifact.getArtifactManifest(), cameraNumber);
		if (mp4Sha256 == null && streamRevision.getStreamPresenceState() != CaptureSyncStreamPresenceState.MISSING) {
			mp4Sha256 = streamRevision.getSourceInputSha256();
		}
		var revisionRefs = new LinkedHashMap<String, Object>();
		revisionRefs.put("capture_sync_revision_id", revision.getCaptureSyncRevisionId());
		revisionRefs.put("capture_sync_stream_revision_id", streamRevision.getCaptureSyncStreamRevisionId());
		revisionRefs.put("capture_sync_job_id", revision.getCaptureSyncJobId());
		revisionRefs.put("sync_frame_index", syncFrameIndex);
		return new CaptureSyncProvenanceResponse(captureSessionId, syncRevisionId, syncFrameIndex, cameraNumber,
				slot.sourceFrameIndex(), slot.sourcePtsNs(), slot.sourcePtsOrdinal(), slot.syncErrorNs(), mp4Sha256,
				slot.cameraStreamId(), slot.videoId(), streamRevision.getSourceMediaReportId(),
				Map.copyOf(revisionRefs));
	}

	private static String provenanceMp4Sha256(Map<String, Object> manifest, int cameraNumber) {
		var rawProvenance = manifest.get("provenance");
		if (!(rawProvenance instanceof Map<?, ?> provenance)
				|| !(provenance.get("cameras") instanceof List<?> cameras)) {
			return null;
		}
		for (var rawCamera : cameras) {
			if (!(rawCamera instanceof Map<?, ?> camera)) {
				continue;
			}
			var value = camera.get("camera_number");
			if (value instanceof Number number && number.intValue() == cameraNumber
					&& camera.get("original_mp4_sha256") instanceof String sha256) {
				return sha256;
			}
		}
		return null;
	}

	private void requireSession(UUID projectId, UUID captureSessionId) {
		this.sessionRepository.findByCaptureSessionIdAndProjectId(captureSessionId, projectId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SESSION_NOT_FOUND",
					"The requested capture session was not found."));
	}

}
