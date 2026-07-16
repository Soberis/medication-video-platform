package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface UploadBatchRepository extends JpaRepository<UploadBatch, UUID> {

	Optional<UploadBatch> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

	Optional<UploadBatch> findByBatchIdAndProjectId(UUID batchId, UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select batch from UploadBatch batch where batch.batchId = :batchId and batch.projectId = :projectId")
	Optional<UploadBatch> findForFileRegistration(
			@Param("batchId") UUID batchId,
			@Param("projectId") UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select batch from UploadBatch batch where batch.batchId = :batchId and batch.projectId = :projectId")
	Optional<UploadBatch> findForCompletion(
			@Param("batchId") UUID batchId,
			@Param("projectId") UUID projectId);
}
