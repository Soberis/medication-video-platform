package com.medicationvideo.platform.annotationreview;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "label_definition")
class AnnotationLabelDefinition {

	@Id
	@Column(name = "label_definition_id", nullable = false, updatable = false)
	private UUID labelDefinitionId;

	@Column(name = "label_schema_id", nullable = false, updatable = false)
	private UUID labelSchemaId;

	@Column(name = "label_id", nullable = false, updatable = false, length = 64)
	private String labelId;

	@Enumerated(EnumType.STRING)
	@Column(name = "label_type", nullable = false, updatable = false, length = 32)
	private AnnotationLabelType labelType;

	@Column(name = "display_name_zh", nullable = false, updatable = false, length = 128)
	private String displayNameZh;

	@Column(name = "description_zh", nullable = false, updatable = false, length = 512)
	private String descriptionZh;

	@Column(name = "sort_order", nullable = false, updatable = false)
	private int sortOrder;

	protected AnnotationLabelDefinition() {
	}

	UUID getLabelSchemaId() {
		return this.labelSchemaId;
	}

	String getLabelId() {
		return this.labelId;
	}

	AnnotationLabelType getLabelType() {
		return this.labelType;
	}

	String getDisplayNameZh() {
		return this.displayNameZh;
	}

	String getDescriptionZh() {
		return this.descriptionZh;
	}

	int getSortOrder() {
		return this.sortOrder;
	}
}

enum AnnotationLabelType {
	PRIMARY_STEP
}
