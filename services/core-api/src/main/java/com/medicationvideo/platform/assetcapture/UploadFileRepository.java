package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface UploadFileRepository extends JpaRepository<UploadFile, UUID> {

	Optional<UploadFile> findByBatchIdAndClientFileId(UUID batchId, UUID clientFileId);

	Optional<UploadFile> findByBatchIdAndIdempotencyKey(UUID batchId, String idempotencyKey);

	long countByBatchId(UUID batchId);

	List<UploadFile> findAllByBatchIdOrderByCreatedAtAscUploadFileIdAsc(UUID batchId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select file from UploadFile file where file.batchId = :batchId order by file.createdAt, file.uploadFileId")
	List<UploadFile> findAllForTermination(@Param("batchId") UUID batchId);

	@Query("select coalesce(sum(file.sizeBytes), 0) from UploadFile file where file.batchId = :batchId")
	long sumSizeBytesByBatchId(@Param("batchId") UUID batchId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select file from UploadFile file where file.batchId = :batchId and file.clientFileId = :clientFileId")
	Optional<UploadFile> findForUploadCommand(@Param("batchId") UUID batchId, @Param("clientFileId") UUID clientFileId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select file from UploadFile file where file.uploadFileId = :uploadFileId")
	Optional<UploadFile> findForCompletion(@Param("uploadFileId") UUID uploadFileId);

}
