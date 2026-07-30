package com.medicationvideo.platform.annotationreview;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AnnotationRevisionRepository extends JpaRepository<AnnotationRevision, UUID> {

	Optional<AnnotationRevision> findByAnnotationTaskIdAndSubmittedByAndIdempotencyKey(
			UUID annotationTaskId,
			String submittedBy,
			String idempotencyKey);

	List<AnnotationRevision> findAllByAnnotationTaskIdIn(Collection<UUID> annotationTaskIds);

	List<AnnotationRevision> findAllByAnnotationRevisionIdIn(Collection<UUID> annotationRevisionIds);

	Optional<AnnotationRevision> findTopByAnnotationTaskIdOrderByRevisionNumberDesc(UUID annotationTaskId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select revision from AnnotationRevision revision
			where revision.projectId = :projectId and revision.annotationRevisionId = :annotationRevisionId
			""")
	Optional<AnnotationRevision> findForUpdate(
			@Param("projectId") UUID projectId,
			@Param("annotationRevisionId") UUID annotationRevisionId);
}
