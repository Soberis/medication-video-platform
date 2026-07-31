package com.medicationvideo.platform.annotationreview;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnotationExportQueryService {

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationLabelSchemaRepository schemaRepository;

	private final AnnotationLabelDefinitionRepository definitionRepository;

	AnnotationExportQueryService(AnnotationRevisionRepository revisionRepository,
			AnnotationLabelSchemaRepository schemaRepository,
			AnnotationLabelDefinitionRepository definitionRepository) {
		this.revisionRepository = revisionRepository;
		this.schemaRepository = schemaRepository;
		this.definitionRepository = definitionRepository;
	}

	@Transactional(readOnly = true)
	public AnnotationExportBundle getExact(UUID projectId, String labelSchemaDigest,
			Collection<AnnotationExportReference> references) {
		var orderedReferences = references.stream()
			.sorted(Comparator.comparing(AnnotationExportReference::annotationRevisionId))
			.toList();
		var revisions = this.revisionRepository
			.findAllByAnnotationRevisionIdIn(
					orderedReferences.stream().map(AnnotationExportReference::annotationRevisionId).toList())
			.stream()
			.collect(Collectors.toMap(AnnotationRevision::getAnnotationRevisionId, Function.identity(),
					(first, second) -> first, LinkedHashMap::new));
		var exportedRevisions = orderedReferences.stream()
			.map(reference -> exactRevision(projectId, labelSchemaDigest, reference, revisions))
			.toList();
		var schema = this.schemaRepository.findBySchemaDigest(labelSchemaDigest)
			.orElseThrow(AnnotationExportQueryService::evidenceChanged);
		var definitions = this.definitionRepository.findAllByLabelSchemaIdOrderBySortOrder(schema.getLabelSchemaId())
			.stream()
			.map(definition -> new AnnotationExportLabel(definition.getLabelId(), definition.getLabelType().name(),
					definition.getDisplayNameZh(), definition.getDescriptionZh(), definition.getSortOrder()))
			.toList();
		return new AnnotationExportBundle(new AnnotationExportSchema(schema.getLabelSchemaId(), schema.getSchemaKey(),
				schema.getSchemaVersion(), schema.getSchemaDigest(), definitions), exportedRevisions);
	}

	private static AnnotationExportRevision exactRevision(UUID projectId, String labelSchemaDigest,
			AnnotationExportReference reference, Map<UUID, AnnotationRevision> revisions) {
		var revision = revisions.get(reference.annotationRevisionId());
		if (revision == null || !revision.getProjectId().equals(projectId)
				|| !revision.getClipAssetId().equals(reference.clipAssetId())
				|| !revision.getDocumentSha256().equals(reference.documentSha256())
				|| !revision.getLabelSchemaDigest().equals(labelSchemaDigest)) {
			throw evidenceChanged();
		}
		return new AnnotationExportRevision(revision.getAnnotationRevisionId(), revision.getClipAssetId(),
				revision.getAnnotationSchemaVersion(), revision.getDocumentSha256(),
				Map.copyOf(revision.getAnnotationDocument()));
	}

	private static IllegalStateException evidenceChanged() {
		return new IllegalStateException("A frozen annotation revision or label schema is unavailable for export.");
	}

	public record AnnotationExportReference(UUID annotationRevisionId, UUID clipAssetId, String documentSha256) {
	}

	public record AnnotationExportBundle(AnnotationExportSchema labelSchema, List<AnnotationExportRevision> revisions) {

		public AnnotationExportBundle {
			revisions = List.copyOf(revisions);
		}
	}

	public record AnnotationExportSchema(UUID labelSchemaId, String schemaKey, String schemaVersion,
			String schemaDigest, List<AnnotationExportLabel> labels) {

		public AnnotationExportSchema {
			labels = List.copyOf(labels);
		}
	}

	public record AnnotationExportLabel(String labelId, String labelType, String displayName, String description,
			int sortOrder) {
	}

	public record AnnotationExportRevision(UUID annotationRevisionId, UUID clipAssetId, String annotationSchemaVersion,
			String documentSha256, Map<String, Object> document) {

		public AnnotationExportRevision {
			document = Map.copyOf(document);
		}
	}

}
