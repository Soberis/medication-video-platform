package com.medicationvideo.platform.annotationreview;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnnotationReviewRepository extends JpaRepository<AnnotationReview, UUID> {

	Optional<AnnotationReview> findByAnnotationRevisionId(UUID annotationRevisionId);

	List<AnnotationReview> findAllByAnnotationRevisionIdIn(Collection<UUID> annotationRevisionIds);

}
