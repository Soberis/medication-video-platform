package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only group-quality boundary exposed to annotation and downstream modules.
 */
@Service
public class CaptureGroupQualityQueryService {

	private final CaptureGroupQualityReviewRepository repository;

	CaptureGroupQualityQueryService(CaptureGroupQualityReviewRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public CurrentDecision getCurrentDecision(UUID projectId, UUID captureGroupId) {
		return this.repository.findTopByProjectIdAndCaptureGroupIdOrderByRevisionDesc(projectId, captureGroupId)
			.map(CurrentDecision::from)
			.orElseGet(() -> CurrentDecision.unreviewed(projectId, captureGroupId));
	}

	@Transactional(readOnly = true)
	public List<CurrentDecision> getCurrentPage(UUID projectId, int page, int size) {
		return this.repository
			.findCurrentPage(projectId,
					org.springframework.data.domain.PageRequest.of(page, Math.min(100, Math.max(1, size))))
			.stream()
			.map(CurrentDecision::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<CurrentDecision> getCurrentDecisions(UUID projectId, List<UUID> captureGroupIds) {
		var distinctIds = captureGroupIds.stream().distinct().toList();
		if (distinctIds.isEmpty()) {
			return List.of();
		}
		var currentByGroup = new LinkedHashMap<UUID, CurrentDecision>();
		this.repository
			.findAllByProjectIdAndCaptureGroupIdInOrderByCaptureGroupIdAscRevisionDesc(projectId, distinctIds)
			.forEach(review -> currentByGroup.putIfAbsent(review.getCaptureGroupId(), CurrentDecision.from(review)));
		return distinctIds.stream()
			.map(captureGroupId -> currentByGroup.getOrDefault(captureGroupId,
					CurrentDecision.unreviewed(projectId, captureGroupId)))
			.toList();
	}

	@Transactional
	public CurrentDecision lockAndGetCurrentDecision(UUID projectId, UUID captureGroupId) {
		return this.repository.findForUpdate(projectId, captureGroupId)
			.stream()
			.findFirst()
			.map(CurrentDecision::from)
			.orElseGet(() -> CurrentDecision.unreviewed(projectId, captureGroupId));
	}

	@Transactional(readOnly = true)
	public List<CurrentDecision> getHistory(UUID projectId, UUID captureGroupId) {
		return this.repository.findAllByProjectIdAndCaptureGroupIdOrderByRevisionAsc(projectId, captureGroupId)
			.stream()
			.map(CurrentDecision::from)
			.toList();
	}

	public record CurrentDecision(UUID captureGroupQualityReviewId, UUID projectId, UUID captureGroupId, String result,
			String reason, String reviewerActorId, long revision, Map<String, Object> confirmedViewSet,
			String inputVersion, String confirmedViewSetSha256, Instant decidedAt) {

		static CurrentDecision from(CaptureGroupQualityReview review) {
			return new CurrentDecision(review.getCaptureGroupQualityReviewId(), review.getProjectId(),
					review.getCaptureGroupId(), review.getResult().name(), review.getReason(),
					review.getReviewerActorId(), review.getRevision(), review.getConfirmedViewSet(),
					review.getInputVersion(), review.getConfirmedViewSetSha256(), review.getDecidedAt());
		}

		static CurrentDecision unreviewed(UUID projectId, UUID captureGroupId) {
			return new CurrentDecision(null, projectId, captureGroupId, "UNREVIEWED", null, null, 0, null, null, null,
					null);
		}

		public boolean isAdmitting() {
			return "QUALIFIED".equals(this.result) || "QUALIFIED_WITH_EXCLUSIONS".equals(this.result);
		}

		public boolean isBlocked() {
			return "NEEDS_REVIEW".equals(this.result) || "REJECTED".equals(this.result);
		}
	}

}
