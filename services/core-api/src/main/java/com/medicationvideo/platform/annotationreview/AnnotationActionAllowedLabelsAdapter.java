package com.medicationvideo.platform.annotationreview;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.ActionAllowedLabelsPort;
import com.medicationvideo.platform.aicandidate.ActionLabelSnapshot;

@Component
class AnnotationActionAllowedLabelsAdapter implements ActionAllowedLabelsPort {

	private final AnnotationLabelSchemaService schemaService;

	private final AnnotationTaskRepository taskRepository;

	AnnotationActionAllowedLabelsAdapter(AnnotationLabelSchemaService schemaService,
			AnnotationTaskRepository taskRepository) {
		this.schemaService = schemaService;
		this.taskRepository = taskRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> resolveAllowedLabels() {
		var schema = this.schemaService.getGlobalCurrent();
		return schema.labels().stream().map(AnnotationLabelDefinitionResponse::labelId).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public ActionLabelSnapshot resolveLabelSnapshot(UUID projectId, UUID captureGroupId) {
		if (projectId != null && captureGroupId != null) {
			var task = this.taskRepository.findByProjectIdAndCaptureGroupId(projectId, captureGroupId);
			if (task.isPresent()) {
				var value = task.get();
				var labels = this.schemaService
					.allowedPrimaryStepLabels(value.getLabelSchemaId(), value.getLabelSchemaDigest())
					.stream()
					.sorted()
					.toList();
				return new ActionLabelSnapshot(value.getAnnotationTaskId(), value.getLabelSchemaId(),
						value.getLabelSchemaDigest(), labels, value.getPrimaryClipAssetId());
			}
		}
		var schema = this.schemaService.getGlobalCurrent();
		return new ActionLabelSnapshot(null, schema.labelSchemaId(), schema.schemaDigest(),
				this.schemaService.allowedPrimaryStepLabels(schema.labelSchemaId(), schema.schemaDigest())
					.stream()
					.sorted()
					.toList());
	}

}
