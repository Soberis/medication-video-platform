package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExternalVideoIngestManifestRepository extends JpaRepository<ExternalVideoIngestManifest, UUID> {

	Optional<ExternalVideoIngestManifest> findByProjectIdAndCaptureGroupKeyAndKeySource(UUID projectId,
			String captureGroupKey, ExternalVideoCaptureGroupKeySource keySource);

}
