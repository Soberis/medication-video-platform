package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureSyncArtifactRepository extends JpaRepository<CaptureSyncArtifact, UUID> {

	Optional<CaptureSyncArtifact> findByCaptureSyncRevisionIdAndArtifactRole(UUID revisionId, String artifactRole);

}
