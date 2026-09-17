package com.medicationvideo.platform.annotationreview;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AnnotationLabelSchemaRepository extends JpaRepository<AnnotationLabelSchema, UUID> {

	Optional<AnnotationLabelSchema> findByStatus(AnnotationLabelSchemaStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select schema from AnnotationLabelSchema schema where schema.status = :status")
	Optional<AnnotationLabelSchema> findByStatusForUpdate(AnnotationLabelSchemaStatus status);

	Optional<AnnotationLabelSchema> findByLabelSchemaIdAndSchemaDigest(UUID labelSchemaId, String schemaDigest);

}
