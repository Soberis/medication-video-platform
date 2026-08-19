package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureSyncRevisionRepository extends JpaRepository<CaptureSyncRevision, UUID> {

	List<CaptureSyncRevision> findAllByCaptureSessionIdOrderByCreatedAtDescCaptureSyncRevisionIdDesc(
			UUID captureSessionId);

	Optional<CaptureSyncRevision> findTopByCaptureSessionIdOrderByCreatedAtDescCaptureSyncRevisionIdDesc(
			UUID captureSessionId);

	Optional<CaptureSyncRevision> findByCaptureSyncJobId(UUID captureSyncJobId);

}
