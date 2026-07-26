package com.medicationvideo.platform.annotationreview;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class AnnotationLabelSchemaService {

	private final ProjectAccessService projectAccessService;
	private final AnnotationLabelSchemaRepository schemaRepository;
	private final AnnotationLabelDefinitionRepository definitionRepository;

	AnnotationLabelSchemaService(
			ProjectAccessService projectAccessService,
			AnnotationLabelSchemaRepository schemaRepository,
			AnnotationLabelDefinitionRepository definitionRepository) {
		this.projectAccessService = projectAccessService;
		this.schemaRepository = schemaRepository;
		this.definitionRepository = definitionRepository;
	}

	@Transactional(readOnly = true)
	AnnotationLabelSchemaResponse getCurrent(UUID projectId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var schema = currentSchema();
		return AnnotationLabelSchemaResponse.from(
				schema,
				this.definitionRepository.findAllByLabelSchemaIdOrderBySortOrder(schema.getLabelSchemaId()));
	}

	@Transactional(readOnly = true)
	AnnotationLabelSchemaBinding currentBinding() {
		var schema = currentSchema();
		return new AnnotationLabelSchemaBinding(schema.getLabelSchemaId(), schema.getSchemaDigest());
	}

	@Transactional(readOnly = true)
	void requireAllowedPrimaryStepLabels(
			UUID labelSchemaId,
			String labelSchemaDigest,
			List<String> labels) {
		var schema = this.schemaRepository.findByLabelSchemaIdAndSchemaDigest(
				labelSchemaId, labelSchemaDigest)
				.orElseThrow(() -> new IllegalStateException("The annotation task label schema is unavailable."));
		var allowedLabels = this.definitionRepository
				.findAllByLabelSchemaIdOrderBySortOrder(schema.getLabelSchemaId())
				.stream()
				.filter(definition -> definition.getLabelType() == AnnotationLabelType.PRIMARY_STEP)
				.map(AnnotationLabelDefinition::getLabelId)
				.collect(java.util.stream.Collectors.toCollection(HashSet::new));
		for (var label : labels) {
			if (!allowedLabels.contains(label.trim())) {
				throw new AnnotationTaskException(
						"ANNOTATION_LABEL_NOT_ALLOWED",
						"The annotation label is not allowed by the task label schema.",
						400);
			}
		}
	}

	private AnnotationLabelSchema currentSchema() {
		return this.schemaRepository.findByStatus(AnnotationLabelSchemaStatus.ACTIVE)
				.orElseThrow(() -> new IllegalStateException("The active annotation label schema is unavailable."));
	}
}

record AnnotationLabelSchemaBinding(UUID labelSchemaId, String schemaDigest) {
}

record AnnotationLabelSchemaResponse(
		UUID labelSchemaId,
		String schemaKey,
		String schemaVersion,
		String schemaDigest,
		String layer,
		List<AnnotationLabelDefinitionResponse> labels) {

	static AnnotationLabelSchemaResponse from(
			AnnotationLabelSchema schema,
			List<AnnotationLabelDefinition> definitions) {
		return new AnnotationLabelSchemaResponse(
				schema.getLabelSchemaId(),
				schema.getSchemaKey(),
				schema.getSchemaVersion(),
				schema.getSchemaDigest(),
				"primary_step",
				definitions.stream().map(AnnotationLabelDefinitionResponse::from).toList());
	}
}

record AnnotationLabelDefinitionResponse(
		String labelId,
		String displayName,
		String description,
		int sortOrder) {

	static AnnotationLabelDefinitionResponse from(AnnotationLabelDefinition definition) {
		return new AnnotationLabelDefinitionResponse(
				definition.getLabelId(),
				definition.getDisplayNameZh(),
				definition.getDescriptionZh(),
				definition.getSortOrder());
	}
}
