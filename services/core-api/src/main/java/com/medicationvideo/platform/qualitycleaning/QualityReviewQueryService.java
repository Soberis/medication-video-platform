package com.medicationvideo.platform.qualitycleaning;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only quality fact exposed to later P0 workflow modules.
 */
@Service
public class QualityReviewQueryService {

	private final QualityReviewRepository repository;

	QualityReviewQueryService(QualityReviewRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public CurrentDecision getCurrentDecision(UUID projectId, UUID clipAssetId) {
		return this.repository.findByProjectIdAndClipAssetId(projectId, clipAssetId)
			.map(review -> new CurrentDecision(review.getDecision().name(), review.getVersion()))
			.orElseGet(() -> new CurrentDecision("UNREVIEWED", 0));
	}

	@Transactional(readOnly = true)
	public Map<UUID, CurrentDecision> findCurrentDecisions(UUID projectId, Collection<UUID> clipAssetIds) {
		if (clipAssetIds.isEmpty()) {
			return Map.of();
		}
		return this.repository.findAllByProjectIdAndClipAssetIdIn(projectId, clipAssetIds)
			.stream()
			.collect(Collectors.toMap(QualityReview::getClipAssetId,
					review -> new CurrentDecision(review.getDecision().name(), review.getVersion()),
					(existing, ignored) -> existing));
	}

	@Transactional
	public CurrentDecision lockAndGetCurrentDecision(UUID projectId, UUID clipAssetId) {
		return this.repository.findForUpdate(projectId, clipAssetId)
			.map(review -> new CurrentDecision(review.getDecision().name(), review.getVersion()))
			.orElseGet(() -> new CurrentDecision("UNREVIEWED", 0));
	}

	public record CurrentDecision(String decision, long version) {

		public boolean isQualified() {
			return "QUALIFIED".equals(this.decision);
		}
	}

}
