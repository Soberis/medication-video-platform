package com.medicationvideo.platform.qualitycleaning;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostCleaningQueryService {

	private final CleaningResultRepository repository;

	PostCleaningQueryService(CleaningResultRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public Map<UUID, PostCleaningResultSummary> findLatestByAnnotationRevisionIds(
			Collection<UUID> annotationRevisionIds) {
		if (annotationRevisionIds.isEmpty()) {
			return Map.of();
		}
		return this.repository.findAllByAnnotationRevisionIdIn(annotationRevisionIds)
				.stream()
				.collect(Collectors.toMap(
						CleaningResult::getAnnotationRevisionId,
						PostCleaningResultSummary::from,
						BinaryOperator.maxBy(java.util.Comparator
								.comparing(PostCleaningResultSummary::evaluatedAt)
								.thenComparing(PostCleaningResultSummary::cleaningResultId))));
	}
}
