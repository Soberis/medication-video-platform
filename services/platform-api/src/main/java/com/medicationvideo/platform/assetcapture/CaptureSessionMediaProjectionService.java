package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Keeps the session-level ingest count, assembly state and technical quality aligned with its
 * persisted camera streams. Callers own the surrounding transaction.
 */
@Service
class CaptureSessionMediaProjectionService {

	private final CaptureSessionRepository sessionRepository;
	private final CameraStreamRepository streamRepository;

	CaptureSessionMediaProjectionService(
			CaptureSessionRepository sessionRepository,
			CameraStreamRepository streamRepository) {
		this.sessionRepository = sessionRepository;
		this.streamRepository = streamRepository;
	}

	void refresh(UUID projectId, UUID captureSessionId, Instant changedAt) {
		var session = this.sessionRepository.findForMediaProjection(captureSessionId, projectId)
				.orElseThrow(() -> new IllegalStateException(
						"A camera stream references a capture session outside its project."));
		var streams = this.streamRepository.findAllByCaptureSessionIdOrderByCameraIndexAsc(
				captureSessionId);
		session.refreshMediaProjection(streams, changedAt);
	}
}
