package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureEventLogRepository extends JpaRepository<CaptureEventLog, UUID> {

}
