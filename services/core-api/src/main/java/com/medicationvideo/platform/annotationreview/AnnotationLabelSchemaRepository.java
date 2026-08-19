package com.medicationvideo.platform.annotationreview;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnnotationLabelSchemaRepository extends JpaRepository<AnnotationLabelSchema, UUID> {

	Optional<AnnotationLabelSchema> findByStatus(AnnotationLabelSchemaStatus status);

	Optional<AnnotationLabelSchema> findByLabelSchemaIdAndSchemaDigest(UUID labelSchemaId, String schemaDigest);

	Optional<AnnotationLabelSchema> findBySchemaDigest(String schemaDigest);

}
