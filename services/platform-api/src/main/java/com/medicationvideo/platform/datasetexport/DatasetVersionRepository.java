package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetVersionRepository extends JpaRepository<DatasetVersion, UUID> {

	Optional<DatasetVersion> findByProjectIdAndCreatedByAndIdempotencyKey(
			UUID projectId, String createdBy, String idempotencyKey);
}
