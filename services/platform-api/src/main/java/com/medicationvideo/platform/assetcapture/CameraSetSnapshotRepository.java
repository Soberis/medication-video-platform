package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CameraSetSnapshotRepository extends JpaRepository<CameraSetSnapshot, UUID> {

	Optional<CameraSetSnapshot> findByCaptureSessionId(UUID captureSessionId);
}
