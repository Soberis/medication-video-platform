package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CaptureSessionRepository extends JpaRepository<CaptureSession, UUID> {

	Optional<CaptureSession> findByBatchIdAndProjectId(UUID batchId, UUID projectId);

	Optional<CaptureSession> findByCaptureSessionIdAndProjectIdAndCaptureGroupWorkflowTrue(UUID captureSessionId,
			UUID projectId);

	List<CaptureSession> findAllByProjectIdAndCaptureGroupWorkflowTrueOrderByCreatedAtDescCaptureSessionIdDesc(
			UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT session
			FROM CaptureSession session
			WHERE session.batchId = :batchId
			  AND session.projectId = :projectId
			  AND session.captureGroupWorkflow = TRUE
			""")
	Optional<CaptureSession> findForReceiptFinalization(@Param("batchId") UUID batchId,
			@Param("projectId") UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT session
			FROM CaptureSession session
			WHERE session.captureSessionId = :captureSessionId
			  AND session.projectId = :projectId
			  AND session.captureGroupWorkflow = TRUE
			""")
	Optional<CaptureSession> findForReceiptFinalizationByCaptureSessionId(
			@Param("captureSessionId") UUID captureSessionId, @Param("projectId") UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT session
			FROM CaptureSession session
			WHERE session.captureSessionId = :captureSessionId
			  AND session.projectId = :projectId
			  AND session.captureGroupWorkflow = TRUE
			""")
	Optional<CaptureSession> findForGroupView(@Param("captureSessionId") UUID captureSessionId,
			@Param("projectId") UUID projectId);

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
