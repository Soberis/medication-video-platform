package com.medicationvideo.platform.mediaprivacy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface MediaReportRepository extends JpaRepository<MediaReport, UUID> {

	Optional<MediaReport> findByMediaJobId(UUID mediaJobId);

	Optional<MediaReport> findTopByProjectIdAndClipAssetIdOrderByCreatedAtDescMediaReportIdDesc(UUID projectId,
			UUID clipAssetId);

}
