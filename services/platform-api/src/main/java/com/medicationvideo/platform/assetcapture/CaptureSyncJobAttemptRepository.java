package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CaptureSyncJobAttemptRepository extends JpaRepository<CaptureSyncJobAttempt, UUID> {

	Optional<CaptureSyncJobAttempt> findTopByCaptureSyncJobIdOrderByAttemptDesc(UUID captureSyncJobId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT attempt FROM CaptureSyncJobAttempt attempt
			WHERE attempt.captureSyncJobId = :jobId
			  AND attempt.attempt = (
			      SELECT MAX(current.attempt) FROM CaptureSyncJobAttempt current
			      WHERE current.captureSyncJobId = :jobId)
			""")
	Optional<CaptureSyncJobAttempt> findCurrentForUpdate(@Param("jobId") UUID jobId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT attempt FROM CaptureSyncJobAttempt attempt
			WHERE attempt.captureSyncJobId = :jobId AND attempt.attempt = :attemptNumber
			""")
	Optional<CaptureSyncJobAttempt> findForUpdate(@Param("jobId") UUID jobId,
			@Param("attemptNumber") int attemptNumber);

	@Query(value = """
			SELECT DISTINCT capture_sync_job_id
			FROM capture_sync_job_attempt
			WHERE status = 'RUNNING' AND lease_until <= :cutoff
			ORDER BY capture_sync_job_id
			LIMIT :batchSize
			""", nativeQuery = true)
	List<UUID> findRecoverableJobIds(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

}
