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

	@Query(value = """
			SELECT a.ai_job_id
			FROM ai_job_attempt a
			JOIN ai_job j ON j.ai_job_id = a.ai_job_id
			WHERE a.status = 'QUEUED'
			  AND j.status = 'QUEUED'
			  AND a.created_at <= :cutoff
			  AND NOT EXISTS (
			      SELECT 1
			      FROM ai_job_attempt newer
			      WHERE newer.ai_job_id = a.ai_job_id
			        AND newer.attempt > a.attempt)
			  AND EXISTS (
			      SELECT 1
			      FROM outbox_event event
			      WHERE event.aggregate_id = a.ai_job_id
			        AND event.aggregate_type = 'AiJob'
			        AND event.event_type IN ('AiHandPoseJobRequestedV1', 'AiActionJobRequestedV1')
			        AND event.schema_version IN ('ai-hand-pose-job.v1', 'ai-action-job.v1')
			        AND event.published_at IS NOT NULL
			        AND event.payload_json ->> 'message_id' = a.message_id::text)
			ORDER BY a.created_at, a.ai_job_id
			LIMIT :batchSize
			""", nativeQuery = true)
	List<UUID> findUndeliveredQueuedJobIds(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

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
