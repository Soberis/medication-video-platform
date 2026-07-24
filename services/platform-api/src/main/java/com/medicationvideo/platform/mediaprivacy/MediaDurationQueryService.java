package com.medicationvideo.platform.mediaprivacy;

import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to the duration accepted by media probing. */
@Service
public class MediaDurationQueryService {

	private final MediaReportRepository reportRepository;

	MediaDurationQueryService(MediaReportRepository reportRepository) {
		this.reportRepository = reportRepository;
	}

	@Transactional(readOnly = true)
	public OptionalLong findVerifiedDurationMicroseconds(UUID projectId, UUID clipAssetId) {
		return this.reportRepository.findByProjectIdAndClipAssetId(projectId, clipAssetId)
				.map(report -> OptionalLong.of(report.getDurationMicroseconds()))
				.orElseGet(OptionalLong::empty);
	}
}
