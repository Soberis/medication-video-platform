package com.medicationvideo.platform.annotationreview;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.ActionAllowedLabelsPort;

@Component
class AnnotationActionAllowedLabelsAdapter implements ActionAllowedLabelsPort {

	private final AnnotationLabelSchemaService schemaService;

	AnnotationActionAllowedLabelsAdapter(AnnotationLabelSchemaService schemaService) {
		this.schemaService = schemaService;
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> resolveAllowedLabels() {
		var schema = this.schemaService.getGlobalCurrent();
		return schema.labels().stream().map(AnnotationLabelDefinitionResponse::labelId).toList();
	}

}
