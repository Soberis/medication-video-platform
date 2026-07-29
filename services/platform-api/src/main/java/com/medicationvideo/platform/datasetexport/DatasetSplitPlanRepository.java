package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetSplitPlanRepository extends JpaRepository<DatasetSplitPlan, UUID> {

	Optional<DatasetSplitPlan> findByDatasetVersionId(UUID datasetVersionId);

	Optional<DatasetSplitPlan> findByProjectIdAndCreatedByAndIdempotencyKey(
			UUID projectId, String createdBy, String idempotencyKey);
}
