package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface DatasetExportAttemptRepository extends JpaRepository<DatasetExportAttempt, UUID> {

	Optional<DatasetExportAttempt> findTopByDatasetExportIdOrderByAttemptDesc(UUID datasetExportId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select attempt from DatasetExportAttempt attempt
			where attempt.datasetExportId = :datasetExportId and attempt.attempt = :attempt
			""")
	Optional<DatasetExportAttempt> findForUpdate(
			@Param("datasetExportId") UUID datasetExportId,
			@Param("attempt") int attempt);
}
