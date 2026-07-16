package com.medicationvideo.platform.mediaprivacy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface MediaJobManualReplayRepository extends JpaRepository<MediaJobManualReplay, UUID> {

	Optional<MediaJobManualReplay> findByProjectIdAndIdempotencyKey(
			UUID projectId, String idempotencyKey);

	Optional<MediaJobManualReplay> findByDeadLetterId(UUID deadLetterId);

	long countByMediaJobId(UUID mediaJobId);
}
