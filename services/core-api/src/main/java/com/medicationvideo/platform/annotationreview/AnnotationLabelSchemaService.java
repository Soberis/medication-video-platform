package com.medicationvideo.platform.annotationreview;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
class AnnotationLabelSchemaService {

	private final ProjectAccessService projectAccessService;

	private final AnnotationLabelSchemaRepository schemaRepository;

	private final AnnotationLabelDefinitionRepository definitionRepository;

	private final AuditEventRecorder auditRecorder;

	AnnotationLabelSchemaService(ProjectAccessService projectAccessService,
			AnnotationLabelSchemaRepository schemaRepository, AnnotationLabelDefinitionRepository definitionRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.schemaRepository = schemaRepository;
		this.definitionRepository = definitionRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	AnnotationLabelSchemaResponse getCurrent(UUID projectId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var schema = currentSchema();
		return AnnotationLabelSchemaResponse.from(schema,
				this.definitionRepository.findAllByLabelSchemaIdOrderBySortOrder(schema.getLabelSchemaId()));
	}

	@Transactional(readOnly = true)
	AnnotationLabelSchemaBinding currentBinding() {
		var schema = currentSchema();
		return new AnnotationLabelSchemaBinding(schema.getLabelSchemaId(), schema.getSchemaDigest());
	}

	@Transactional
	AnnotationLabelSchemaResponse addPrimaryStepLabel(UUID projectId, String actorId, String labelId,
			String displayName, String description, String requestId, String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var current = this.schemaRepository.findByStatusForUpdate(AnnotationLabelSchemaStatus.ACTIVE)
			.orElseThrow(() -> new IllegalStateException("The active annotation label schema is unavailable."));
		var definitions = this.definitionRepository.findAllByLabelSchemaIdOrderBySortOrder(current.getLabelSchemaId());
		if (definitions.stream().anyMatch(definition -> definition.getLabelId().equals(labelId))) {
			throw new AnnotationTaskException("ANNOTATION_LABEL_ALREADY_EXISTS",
					"The annotation label already exists in the active label library.", 409);
		}
		var now = java.time.Instant.now();
		var schemaId = UUID.randomUUID();
		var allDefinitions = new java.util.ArrayList<>(definitions);
		allDefinitions.add(AnnotationLabelDefinition.define(UUID.randomUUID(), schemaId, labelId, displayName,
				description, definitions.size() + 1));
		var digest = schemaDigest(allDefinitions);
		current.retire();
		this.schemaRepository.saveAndFlush(current);
		var published = this.schemaRepository.save(AnnotationLabelSchema.publish(schemaId, current.getSchemaKey(),
				nextSchemaVersion(current.getSchemaVersion()), digest, now));
		this.definitionRepository.saveAll(allDefinitions.stream()
			.map(definition -> AnnotationLabelDefinition.define(UUID.randomUUID(), schemaId, definition.getLabelId(),
					definition.getDisplayNameZh(), definition.getDescriptionZh(), definition.getSortOrder()))
			.toList());
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"ANNOTATION_LABEL_SCHEMA_PUBLISHED", "AnnotationLabelSchema", schemaId.toString(),
				published.getSchemaDigest(), AuditOutcome.SUCCEEDED, "EXPLICIT_LABEL_LIBRARY_CHANGE", requestId,
				traceId, "PROJECT_MEMBERSHIP_ADMIN",
				Map.of("label_id", labelId, "previous_label_schema_id", current.getLabelSchemaId().toString())));
		return AnnotationLabelSchemaResponse.from(published,
				this.definitionRepository.findAllByLabelSchemaIdOrderBySortOrder(schemaId));
	}

	@Transactional(readOnly = true)
	void requireAllowedPrimaryStepLabels(UUID labelSchemaId, String labelSchemaDigest, List<String> labels) {
		var allowedLabels = allowedPrimaryStepLabels(labelSchemaId, labelSchemaDigest);
		for (var label : labels) {
			if (!allowedLabels.contains(label.trim())) {
				throw new AnnotationTaskException("ANNOTATION_LABEL_NOT_ALLOWED",
						"The annotation label is not allowed by the task label schema.", 400);
			}
		}
	}

	@Transactional(readOnly = true)
	java.util.Set<String> allowedPrimaryStepLabels(UUID labelSchemaId, String labelSchemaDigest) {
		var schema = this.schemaRepository.findByLabelSchemaIdAndSchemaDigest(labelSchemaId, labelSchemaDigest)
			.orElseThrow(() -> new IllegalStateException("The annotation task label schema is unavailable."));
		return this.definitionRepository.findAllByLabelSchemaIdOrderBySortOrder(schema.getLabelSchemaId())
			.stream()
			.filter(definition -> definition.getLabelType() == AnnotationLabelType.PRIMARY_STEP)
			.map(AnnotationLabelDefinition::getLabelId)
			.collect(java.util.stream.Collectors.toCollection(HashSet::new));
	}

	private AnnotationLabelSchema currentSchema() {
		return this.schemaRepository.findByStatus(AnnotationLabelSchemaStatus.ACTIVE)
			.orElseThrow(() -> new IllegalStateException("The active annotation label schema is unavailable."));
	}

	private static String nextSchemaVersion(String currentVersion) {
		var matcher = java.util.regex.Pattern.compile("^(.*\\.v)([0-9]+)$").matcher(currentVersion);
		if (!matcher.matches()) {
			throw new IllegalStateException("The active annotation label schema version is invalid.");
		}
		try {
			return matcher.group(1) + Math.incrementExact(Integer.parseInt(matcher.group(2)));
		}
		catch (NumberFormatException exception) {
			throw new IllegalStateException("The active annotation label schema version is invalid.", exception);
		}
	}

	private static String schemaDigest(List<AnnotationLabelDefinition> definitions) {
		var content = definitions.stream()
			.sorted(java.util.Comparator.comparingInt(AnnotationLabelDefinition::getSortOrder))
			.map(definition -> String.join("|", definition.getLabelType().name().toLowerCase(java.util.Locale.ROOT),
					definition.getLabelId(), definition.getDisplayNameZh(), definition.getDescriptionZh(),
					Integer.toString(definition.getSortOrder())) + "\n")
			.collect(java.util.stream.Collectors.joining());
		try {
			return java.util.HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

}

record AnnotationLabelSchemaBinding(UUID labelSchemaId, String schemaDigest) {
}

record AnnotationLabelSchemaResponse(UUID labelSchemaId, String schemaKey, String schemaVersion, String schemaDigest,
		String layer, List<AnnotationLabelDefinitionResponse> labels) {

	static AnnotationLabelSchemaResponse from(AnnotationLabelSchema schema,
			List<AnnotationLabelDefinition> definitions) {
		return new AnnotationLabelSchemaResponse(schema.getLabelSchemaId(), schema.getSchemaKey(),
				schema.getSchemaVersion(), schema.getSchemaDigest(), "primary_step",
				definitions.stream().map(AnnotationLabelDefinitionResponse::from).toList());
	}
}

record AnnotationLabelDefinitionResponse(String labelId, String displayName, String description, int sortOrder) {

	static AnnotationLabelDefinitionResponse from(AnnotationLabelDefinition definition) {
		return new AnnotationLabelDefinitionResponse(definition.getLabelId(), definition.getDisplayNameZh(),
				definition.getDescriptionZh(), definition.getSortOrder());
	}
}
