package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface MediaJobAttemptRepository extends JpaRepository<MediaJobAttempt, UUID> {

	java.util.Optional<MediaJobAttempt> findTopByMediaJobIdOrderByAttemptDesc(UUID mediaJobId);

	@Query(value = """
			SELECT DISTINCT media_job_id
			FROM media_job_attempt
			WHERE status = 'RUNNING' AND lease_until <= :cutoff
			ORDER BY media_job_id
			LIMIT :batchSize
			""", nativeQuery = true)
	List<UUID> findRecoverableJobIds(
			@Param("cutoff") Instant cutoff,
			@Param("batchSize") int batchSize);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT attempt FROM MediaJobAttempt attempt
			WHERE attempt.mediaJobId = :mediaJobId
			  AND attempt.attempt = (
			      SELECT MAX(current.attempt) FROM MediaJobAttempt current
			      WHERE current.mediaJobId = :mediaJobId)
			""")
	java.util.Optional<MediaJobAttempt> findCurrentForUpdate(
			@Param("mediaJobId") UUID mediaJobId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT attempt FROM MediaJobAttempt attempt
			WHERE attempt.mediaJobId = :mediaJobId AND attempt.attempt = :attemptNumber
			""")
	java.util.Optional<MediaJobAttempt> findForUpdate(
			@Param("mediaJobId") UUID mediaJobId,
			@Param("attemptNumber") int attemptNumber);
}
