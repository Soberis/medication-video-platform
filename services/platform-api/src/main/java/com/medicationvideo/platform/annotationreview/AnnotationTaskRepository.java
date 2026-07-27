package com.medicationvideo.platform.annotationreview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AnnotationTaskRepository extends JpaRepository<AnnotationTask, UUID> {

	Optional<AnnotationTask> findByProjectIdAndCreatedByAndIdempotencyKey(
			UUID projectId,
			String createdBy,
			String idempotencyKey);

	boolean existsByProjectIdAndClipAssetId(UUID projectId, UUID clipAssetId);

	List<AnnotationTask> findAllByProjectIdAndClipAssetIdIn(UUID projectId, List<UUID> clipAssetIds);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select task from AnnotationTask task
			where task.projectId = :projectId and task.annotationTaskId = :annotationTaskId
			""")
	Optional<AnnotationTask> findForUpdate(
			@Param("projectId") UUID projectId,
			@Param("annotationTaskId") UUID annotationTaskId);

	List<AnnotationTask> findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(UUID projectId);

	List<AnnotationTask> findAllByProjectIdAndAssigneeActorIdOrderByCreatedAtDescAnnotationTaskIdDesc(
			UUID projectId,
			String assigneeActorId);
}
