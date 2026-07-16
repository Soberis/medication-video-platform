package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureSessionRepository extends JpaRepository<CaptureSession, UUID> {

	Optional<CaptureSession> findByBatchIdAndProjectId(UUID batchId, UUID projectId);

	Optional<CaptureSession> findByCaptureSessionIdAndProjectId(UUID captureSessionId, UUID projectId);
}
