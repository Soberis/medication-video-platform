package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.Optional;
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
		return findLatestVerifiedDurationFact(projectId, clipAssetId)
				.map(fact -> OptionalLong.of(fact.durationMicroseconds()))
				.orElseGet(OptionalLong::empty);
	}

	@Transactional(readOnly = true)
	public Optional<VerifiedDurationFact> findLatestVerifiedDurationFact(
			UUID projectId,
			UUID clipAssetId) {
		return this.reportRepository
				.findTopByProjectIdAndClipAssetIdOrderByCreatedAtDescMediaReportIdDesc(
						projectId, clipAssetId)
				.map(VerifiedDurationFact::from);
	}

	public record VerifiedDurationFact(
			UUID mediaReportId,
			String inputDigest,
			String recipeDigest,
			long durationMicroseconds,
			Instant createdAt) {

		private static VerifiedDurationFact from(MediaReport report) {
			return new VerifiedDurationFact(
					report.getMediaReportId(),
					report.getInputDigest(),
					report.getRecipeDigest(),
					report.getDurationMicroseconds(),
					report.getCreatedAt());
		}
	}
}
