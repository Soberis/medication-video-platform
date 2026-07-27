package com.medicationvideo.platform.annotationreview;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnnotationLabelDefinitionRepository extends JpaRepository<AnnotationLabelDefinition, UUID> {

	List<AnnotationLabelDefinition> findAllByLabelSchemaIdOrderBySortOrder(UUID labelSchemaId);
}
