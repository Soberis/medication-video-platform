package com.medicationvideo.platform.assetcapture;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CameraChannelRepository extends JpaRepository<CameraChannel, UUID> {

	Optional<CameraChannel> findByProjectIdAndProvisioningKey(UUID projectId, String provisioningKey);

	List<CameraChannel> findAllByProjectIdAndCameraChannelIdIn(
			UUID projectId,
			Collection<UUID> cameraChannelIds);
}
