package com.medicationvideo.platform.annotationreview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnnotationTaskRepository extends JpaRepository<AnnotationTask, UUID> {

	Optional<AnnotationTask> findByProjectIdAndCreatedByAndIdempotencyKey(
			UUID projectId,
			String createdBy,
			String idempotencyKey);

	boolean existsByProjectIdAndClipAssetId(UUID projectId, UUID clipAssetId);

	List<AnnotationTask> findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(UUID projectId);

	List<AnnotationTask> findAllByProjectIdAndAssigneeActorIdOrderByCreatedAtDescAnnotationTaskIdDesc(
			UUID projectId,
			String assigneeActorId);
}
