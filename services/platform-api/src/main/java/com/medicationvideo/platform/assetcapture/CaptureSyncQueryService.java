package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureSyncQueryService {

	private final CaptureSessionRepository sessionRepository;

	private final CaptureSyncRevisionRepository revisionRepository;

	private final CaptureSyncStreamRevisionRepository streamRevisionRepository;

	private final CaptureSyncJobRepository jobRepository;

	private final CaptureSyncArtifactRepository artifactRepository;

	private final SynchronizedFrameQueryPort frameQueryPort;

	CaptureSyncQueryService(CaptureSessionRepository sessionRepository,
			CaptureSyncRevisionRepository revisionRepository,
			CaptureSyncStreamRevisionRepository streamRevisionRepository, CaptureSyncJobRepository jobRepository,
			CaptureSyncArtifactRepository artifactRepository, SynchronizedFrameQueryPort frameQueryPort) {
		this.sessionRepository = sessionRepository;
		this.revisionRepository = revisionRepository;
		this.streamRevisionRepository = streamRevisionRepository;
		this.jobRepository = jobRepository;
		this.artifactRepository = artifactRepository;
		this.frameQueryPort = frameQueryPort;
	}

	@Transactional(readOnly = true)
	CaptureSyncTimelineResponse getTimeline(UUID projectId, UUID captureSessionId) {
		requireSession(projectId, captureSessionId);
		var currentRevisionId = this.sessionRepository.findByCaptureSessionIdAndProjectId(captureSessionId, projectId)
			.map(CaptureSession::getCurrentSyncRevisionId)
			.orElse(null);
		var latestJob = this.jobRepository
			.findTopByProjectIdAndCaptureSessionIdOrderByCreatedAtDescCaptureSyncJobIdDesc(projectId, captureSessionId)
			.orElse(null);
		if (currentRevisionId == null) {
			var syncStatus = latestJob == null ? "NOT_SYNCHRONIZED" : switch (latestJob.getStatus()) {
				case QUEUED, RUNNING, RETRY_WAIT -> "SYNCHRONIZING";
				case FAILED -> "FAILED";
				case SUCCEEDED -> "NOT_SYNCHRONIZED";
			};
			return CaptureSyncTimelineResponse.withoutRevision(captureSessionId, syncStatus,
					latestJob == null ? null : latestJob.getStatus().name());
		}
		var revision = this.revisionRepository.findById(currentRevisionId)
			.filter(candidate -> candidate.getProjectId().equals(projectId)
					&& candidate.getCaptureSessionId().equals(captureSessionId))
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_REVISION_NOT_FOUND",
					"The current synchronization revision was not found."));
		var streams = this.streamRevisionRepository
			.findAllByCaptureSyncRevisionIdOrderByCameraNumberAsc(currentRevisionId);
		return CaptureSyncTimelineResponse.fromRevision(captureSessionId, revision, streams,
				latestJob == null ? null : latestJob.getStatus().name());
	}

	@Transactional(readOnly = true)
	CaptureSyncFramePageResponse listFrames(UUID projectId, UUID captureSessionId, UUID syncRevisionId,
			Long fromSyncFrameIndex, List<Integer> cameraNumbers, String cursor, int limit) {
		requireSession(projectId, captureSessionId);
		var revisionId = syncRevisionId != null ? syncRevisionId
				: this.sessionRepository.findByCaptureSessionIdAndProjectId(captureSessionId, projectId)
					.map(CaptureSession::getCurrentSyncRevisionId)
					.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_REVISION_NOT_FOUND",
							"No accepted synchronization revision is available."));
		var revision = this.revisionRepository.findById(revisionId)
			.filter(candidate -> candidate.getProjectId().equals(projectId)
					&& candidate.getCaptureSessionId().equals(captureSessionId))
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_REVISION_NOT_FOUND",
					"The requested synchronization revision was not found."));
		var artifact = this.artifactRepository
			.findByCaptureSyncRevisionIdAndArtifactRole(revision.getCaptureSyncRevisionId(),
					CaptureSyncArtifact.ARTIFACT_ROLE)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_ARTIFACT_NOT_FOUND",
					"The synchronized-frame artifact was not found."));
		var page = this.frameQueryPort.query(artifact,
				new SynchronizedFrameQueryPort.FrameQueryRequest(captureSessionId, revision.getCaptureSyncRevisionId(),
						fromSyncFrameIndex, cameraNumbers, cursor, limit));
		return new CaptureSyncFramePageResponse(revision.getCaptureSyncRevisionId(), page.items(), page.nextCursor());
	}

	private void requireSession(UUID projectId, UUID captureSessionId) {
		this.sessionRepository.findByCaptureSessionIdAndProjectId(captureSessionId, projectId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SESSION_NOT_FOUND",
					"The requested capture session was not found."));
	}

}
