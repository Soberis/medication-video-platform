package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
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

	@Query(value = """
			SELECT batch.*
			FROM upload_batch batch
			WHERE batch.status IN ('INITIATED', 'UPLOADING')
			  AND batch.expires_at <= :now
			  AND NOT EXISTS (
			      SELECT 1
			      FROM upload_file file
			      WHERE file.batch_id = batch.batch_id
			        AND file.status = 'COMPLETING'
			        AND file.completion_lease_expires_at > :now)
			ORDER BY batch.expires_at, batch.batch_id
			LIMIT 1
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<UploadBatch> findNextExpiredForUpdate(@Param("now") Instant now);

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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select batch from UploadBatch batch where batch.batchId = :batchId and batch.projectId = :projectId")
	Optional<UploadBatch> findForTermination(
			@Param("batchId") UUID batchId,
			@Param("projectId") UUID projectId);
}
