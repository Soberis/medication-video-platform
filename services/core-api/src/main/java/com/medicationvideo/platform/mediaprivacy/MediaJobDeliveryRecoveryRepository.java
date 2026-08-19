package com.medicationvideo.platform.mediaprivacy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface MediaJobDeliveryRecoveryRepository extends JpaRepository<MediaJobDeliveryRecovery, UUID> {

	Optional<MediaJobDeliveryRecovery> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

	Optional<MediaJobDeliveryRecovery> findBySourceMessageId(UUID sourceMessageId);

}
