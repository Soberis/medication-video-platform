package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface MediaJobAttemptRepository extends JpaRepository<MediaJobAttempt, UUID> {

	java.util.Optional<MediaJobAttempt> findTopByMediaJobIdOrderByAttemptDesc(UUID mediaJobId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT attempt FROM MediaJobAttempt attempt
			WHERE attempt.mediaJobId = :mediaJobId AND attempt.attempt = :attemptNumber
			""")
	java.util.Optional<MediaJobAttempt> findForUpdate(
			@Param("mediaJobId") UUID mediaJobId,
			@Param("attemptNumber") int attemptNumber);
}
