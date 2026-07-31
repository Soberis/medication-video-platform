package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetBuildSnapshotRepository extends JpaRepository<DatasetBuildSnapshot, UUID> {

	Optional<DatasetBuildSnapshot> findByDatasetVersionId(UUID datasetVersionId);

	Optional<DatasetBuildSnapshot> findByProjectIdAndCreatedByAndIdempotencyKey(UUID projectId, String createdBy,
			String idempotencyKey);

}
