package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExternalVideoIngestManifestVideoRepository extends JpaRepository<ExternalVideoIngestManifestVideo, UUID> {

	List<ExternalVideoIngestManifestVideo> findAllByManifestIdOrderByCameraNumber(UUID manifestId);

	Optional<ExternalVideoIngestManifestVideo> findByManifestIdAndClientFileId(UUID manifestId, UUID clientFileId);

}
