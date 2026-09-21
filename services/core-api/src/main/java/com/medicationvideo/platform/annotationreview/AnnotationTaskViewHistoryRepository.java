package com.medicationvideo.platform.annotationreview;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnnotationTaskViewHistoryRepository extends JpaRepository<AnnotationTaskViewHistory, UUID> {

	Optional<AnnotationTaskViewHistory> findByAnnotationTaskIdAndCameraNumberAndActorIdAndIdempotencyKey(
			UUID annotationTaskId, int cameraNumber, String actorId, String idempotencyKey);

}
