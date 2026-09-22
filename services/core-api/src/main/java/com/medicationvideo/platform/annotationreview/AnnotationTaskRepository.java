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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select task from AnnotationTask task
			where task.projectId = :projectId and task.captureGroupId = :captureGroupId
			""")
	Optional<AnnotationTask> findForUpdateByProjectIdAndCaptureGroupId(@Param("projectId") UUID projectId,
			@Param("captureGroupId") UUID captureGroupId);

	Optional<AnnotationTask> findByProjectIdAndCaptureGroupId(UUID projectId, UUID captureGroupId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select task from AnnotationTask task
			where task.projectId = :projectId and task.annotationTaskId = :annotationTaskId
			""")
	Optional<AnnotationTask> findForUpdate(@Param("projectId") UUID projectId,
			@Param("annotationTaskId") UUID annotationTaskId);

	List<AnnotationTask> findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(UUID projectId);

}
