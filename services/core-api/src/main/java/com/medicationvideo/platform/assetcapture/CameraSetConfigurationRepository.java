package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CameraSetConfigurationRepository extends JpaRepository<CameraSetConfiguration, UUID> {

	Optional<CameraSetConfiguration> findByCameraSetIdAndProjectId(UUID cameraSetId, UUID projectId);

	Optional<CameraSetConfiguration> findByProjectIdAndCameraSetKeyAndConfigurationVersion(UUID projectId,
			String cameraSetKey, int configurationVersion);

}
