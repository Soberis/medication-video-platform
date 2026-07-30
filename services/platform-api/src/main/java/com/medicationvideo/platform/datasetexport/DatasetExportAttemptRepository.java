package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface DatasetExportAttemptRepository extends JpaRepository<DatasetExportAttempt, UUID> {

	Optional<DatasetExportAttempt> findTopByDatasetExportIdOrderByAttemptDesc(UUID datasetExportId);

	@Query(value = """
			SELECT DISTINCT dataset_export_id
			FROM dataset_export_attempt
			WHERE status = 'RUNNING' AND lease_until <= :cutoff
			ORDER BY dataset_export_id
			LIMIT :batchSize
			""", nativeQuery = true)
	List<UUID> findRecoverableExportIds(
			@Param("cutoff") Instant cutoff,
			@Param("batchSize") int batchSize);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select attempt from DatasetExportAttempt attempt
			where attempt.datasetExportId = :datasetExportId
			  and attempt.attempt = (
			      select max(current.attempt) from DatasetExportAttempt current
			      where current.datasetExportId = :datasetExportId)
			""")
	Optional<DatasetExportAttempt> findCurrentForUpdate(
			@Param("datasetExportId") UUID datasetExportId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select attempt from DatasetExportAttempt attempt
			where attempt.datasetExportId = :datasetExportId and attempt.attempt = :attempt
			""")
	Optional<DatasetExportAttempt> findForUpdate(
			@Param("datasetExportId") UUID datasetExportId,
			@Param("attempt") int attempt);
}
