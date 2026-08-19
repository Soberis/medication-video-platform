package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CaptureSessionRepository extends JpaRepository<CaptureSession, UUID> {

	Optional<CaptureSession> findByBatchIdAndProjectId(UUID batchId, UUID projectId);

	Optional<CaptureSession> findByCaptureSessionIdAndProjectId(UUID captureSessionId, UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT session
			FROM CaptureSession session
			WHERE session.captureSessionId = :captureSessionId
			  AND session.projectId = :projectId
			""")
	Optional<CaptureSession> findForMediaProjection(@Param("captureSessionId") UUID captureSessionId,
			@Param("projectId") UUID projectId);

}
