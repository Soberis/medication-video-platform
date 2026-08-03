package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetExportManualReplayRepository extends JpaRepository<DatasetExportManualReplay, UUID> {

	Optional<DatasetExportManualReplay> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

	Optional<DatasetExportManualReplay> findByDeadLetterId(UUID deadLetterId);

	long countByDatasetExportId(UUID datasetExportId);

}
