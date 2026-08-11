package com.medicationvideo.platform.aicandidate;

import java.util.UUID;
import java.util.Optional;
import java.util.List;

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

}
