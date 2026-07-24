package com.medicationvideo.platform.annotationreview;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnnotationRevisionRepository extends JpaRepository<AnnotationRevision, UUID> {

	Optional<AnnotationRevision> findByAnnotationTaskIdAndSubmittedByAndIdempotencyKey(
			UUID annotationTaskId,
			String submittedBy,
			String idempotencyKey);
}
