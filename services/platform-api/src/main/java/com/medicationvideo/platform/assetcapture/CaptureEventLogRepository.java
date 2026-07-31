package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureEventLogRepository extends JpaRepository<CaptureEventLog, UUID> {

	Optional<CaptureEventLog> findByCaptureEventLogIdAndProjectId(UUID captureEventLogId, UUID projectId);

}
