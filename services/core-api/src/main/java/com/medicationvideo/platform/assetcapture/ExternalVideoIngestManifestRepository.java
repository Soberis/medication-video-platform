package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface ExternalVideoIngestManifestRepository extends JpaRepository<ExternalVideoIngestManifest, UUID> {

	Optional<ExternalVideoIngestManifest> findByProjectIdAndCaptureGroupKeyAndKeySource(UUID projectId,
			String captureGroupKey, ExternalVideoCaptureGroupKeySource keySource);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT manifest
			FROM ExternalVideoIngestManifest manifest
			WHERE manifest.manifestId = :manifestId
			  AND manifest.projectId = :projectId
			""")
	Optional<ExternalVideoIngestManifest> findForBinding(@Param("manifestId") UUID manifestId,
			@Param("projectId") UUID projectId);

}
