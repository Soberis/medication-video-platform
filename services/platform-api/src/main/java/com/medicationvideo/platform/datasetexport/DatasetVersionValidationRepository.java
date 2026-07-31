package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetVersionValidationRepository extends JpaRepository<DatasetVersionValidation, UUID> {

	Optional<DatasetVersionValidation> findByDatasetVersionId(UUID datasetVersionId);

	Optional<DatasetVersionValidation> findByProjectIdAndValidatedByAndIdempotencyKey(UUID projectId,
			String validatedBy, String idempotencyKey);

}
