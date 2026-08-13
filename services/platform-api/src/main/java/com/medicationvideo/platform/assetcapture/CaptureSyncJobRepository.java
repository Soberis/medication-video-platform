package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CaptureSyncJobRepository extends JpaRepository<CaptureSyncJob, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT job FROM CaptureSyncJob job WHERE job.captureSyncJobId = :jobId")
	Optional<CaptureSyncJob> findForUpdate(@Param("jobId") UUID jobId);

	Optional<CaptureSyncJob> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

	Optional<CaptureSyncJob> findByProjectIdAndCaptureSessionIdAndInputManifestSha256AndSyncProfileSha256AndAlgorithmKeyAndAlgorithmVersion(
			UUID projectId, UUID captureSessionId, String inputManifestSha256, String syncProfileSha256,
			String algorithmKey, String algorithmVersion);

	Optional<CaptureSyncJob> findTopByProjectIdAndCaptureSessionIdOrderByCreatedAtDescCaptureSyncJobIdDesc(
			UUID projectId, UUID captureSessionId);

}
