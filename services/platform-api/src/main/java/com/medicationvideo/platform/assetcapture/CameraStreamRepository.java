package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CameraStreamRepository extends JpaRepository<CameraStream, UUID> {

	List<CameraStream> findAllByCaptureSessionIdOrderByCameraIndexAsc(UUID captureSessionId);

	Optional<CameraStream> findByClipAssetId(UUID clipAssetId);

}
