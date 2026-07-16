package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface MediaReportRepository extends JpaRepository<MediaReport, UUID> {
}
