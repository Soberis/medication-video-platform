package com.medicationvideo.platform.qualitycleaning;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only CaptureGroup event-cleaning boundary for task summaries and downstream
 * export.
 */
@Service
public class CaptureGroupCleaningQueryService {

	private final CaptureGroupCleaningRepository repository;

	CaptureGroupCleaningQueryService(CaptureGroupCleaningRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public Map<UUID, CaptureGroupCleaningSummary> findLatestByAnnotationRevisionIds(
			Collection<UUID> annotationRevisionIds) {
		if (annotationRevisionIds.isEmpty()) {
			return Map.of();
		}
		return this.repository.findAllByAnnotationRevisionIdIn(annotationRevisionIds)
			.stream()
			.collect(Collectors.toMap(CaptureGroupCleaning::getAnnotationRevisionId, CaptureGroupCleaningSummary::from,
					BinaryOperator.maxBy(java.util.Comparator.comparing(CaptureGroupCleaningSummary::evaluatedAt)
						.thenComparing(CaptureGroupCleaningSummary::captureGroupCleaningId))));
	}

	@Transactional(readOnly = true)
	public CaptureGroupCleaningSummary findLatestByCaptureGroup(UUID projectId, UUID captureGroupId) {
		return this.repository
			.findTopByProjectIdAndCaptureGroupIdOrderByEvaluatedAtDescCaptureGroupCleaningIdDesc(projectId,
					captureGroupId)
			.map(CaptureGroupCleaningSummary::from)
			.orElse(null);
	}

}
