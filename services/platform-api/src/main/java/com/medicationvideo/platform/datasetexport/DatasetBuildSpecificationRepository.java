package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetBuildSpecificationRepository extends JpaRepository<DatasetBuildSpecification, UUID> {

	Optional<DatasetBuildSpecification> findByDatasetVersionId(UUID datasetVersionId);

	Optional<DatasetBuildSpecification> findByProjectIdAndCreatedByAndIdempotencyKey(UUID projectId, String createdBy,
			String idempotencyKey);

}
