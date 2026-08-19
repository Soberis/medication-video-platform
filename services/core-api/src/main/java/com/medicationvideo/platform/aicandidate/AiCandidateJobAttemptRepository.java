package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AiCandidateJobAttemptRepository extends JpaRepository<AiCandidateJobAttempt, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<AiCandidateJobAttempt> findByAiJobIdAndAttempt(@Param("aiJobId") UUID aiJobId,
			@Param("attempt") int attempt);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select attempt from AiCandidateJobAttempt attempt where attempt.aiJobId = :aiJobId order by attempt.attempt desc")
	List<AiCandidateJobAttempt> findLatestForUpdate(@Param("aiJobId") UUID aiJobId);

	Optional<AiCandidateJobAttempt> findFirstByAiJobIdOrderByAttemptDesc(UUID aiJobId);

	@Query(value = """
			SELECT DISTINCT ai_job_id
			FROM ai_job_attempt
			WHERE status = 'RUNNING' AND lease_until <= :cutoff
			ORDER BY ai_job_id
			LIMIT :batchSize
			""", nativeQuery = true)
	List<UUID> findRecoverableJobIds(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT attempt FROM AiCandidateJobAttempt attempt
			WHERE attempt.aiJobId = :aiJobId
			  AND attempt.attempt = (
			      SELECT MAX(current.attempt) FROM AiCandidateJobAttempt current
			      WHERE current.aiJobId = :aiJobId)
			""")
	Optional<AiCandidateJobAttempt> findCurrentForUpdate(@Param("aiJobId") UUID aiJobId);

}
