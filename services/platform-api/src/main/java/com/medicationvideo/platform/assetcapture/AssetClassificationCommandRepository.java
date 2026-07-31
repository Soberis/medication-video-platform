package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AssetClassificationCommandRepository extends JpaRepository<AssetClassificationCommand, UUID> {

	Optional<AssetClassificationCommand> findByProjectIdAndPerformedByAndIdempotencyKey(UUID projectId,
			String performedBy, String idempotencyKey);

}
