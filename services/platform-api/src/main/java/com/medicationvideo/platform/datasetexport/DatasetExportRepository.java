package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface DatasetExportRepository extends JpaRepository<DatasetExport, UUID> {

	Optional<DatasetExport> findByProjectIdAndRequestedByAndIdempotencyKey(
			UUID projectId, String requestedBy, String idempotencyKey);

	Optional<DatasetExport> findByDatasetVersionId(UUID datasetVersionId);

	Optional<DatasetExport> findByProjectIdAndApprovedByAndApprovalIdempotencyKey(
			UUID projectId, String approvedBy, String approvalIdempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select export from DatasetExport export
			where export.datasetExportId = :datasetExportId
			""")
	Optional<DatasetExport> findForUpdate(@Param("datasetExportId") UUID datasetExportId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select export from DatasetExport export
			where export.projectId = :projectId and export.datasetVersionId = :datasetVersionId
			""")
	Optional<DatasetExport> findForUpdateByProjectAndVersion(
			@Param("projectId") UUID projectId,
			@Param("datasetVersionId") UUID datasetVersionId);
}
