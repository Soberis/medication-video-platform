package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetExportRepository extends JpaRepository<DatasetExport, UUID> {

	Optional<DatasetExport> findByProjectIdAndRequestedByAndIdempotencyKey(
			UUID projectId, String requestedBy, String idempotencyKey);

	Optional<DatasetExport> findByDatasetVersionId(UUID datasetVersionId);
}
