package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "label_schema")
class AnnotationLabelSchema {

	@Id
	@Column(name = "label_schema_id", nullable = false, updatable = false)
	private UUID labelSchemaId;

	@Column(name = "schema_key", nullable = false, updatable = false, length = 64)
	private String schemaKey;

	@Column(name = "schema_version", nullable = false, updatable = false, length = 64)
	private String schemaVersion;

	@Column(name = "schema_digest", nullable = false, updatable = false, length = 64)
	private String schemaDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private AnnotationLabelSchemaStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AnnotationLabelSchema() {
	}

	static AnnotationLabelSchema publish(UUID labelSchemaId, String schemaKey, String schemaVersion,
			String schemaDigest, Instant createdAt) {
		var schema = new AnnotationLabelSchema();
		schema.labelSchemaId = labelSchemaId;
		schema.schemaKey = schemaKey;
		schema.schemaVersion = schemaVersion;
		schema.schemaDigest = schemaDigest;
		schema.status = AnnotationLabelSchemaStatus.ACTIVE;
		schema.createdAt = createdAt;
		return schema;
	}

	UUID getLabelSchemaId() {
		return this.labelSchemaId;
	}

	String getSchemaKey() {
		return this.schemaKey;
	}

	String getSchemaVersion() {
		return this.schemaVersion;
	}

	String getSchemaDigest() {
		return this.schemaDigest;
	}

	AnnotationLabelSchemaStatus getStatus() {
		return this.status;
	}

	void retire() {
		this.status = AnnotationLabelSchemaStatus.RETIRED;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

}

enum AnnotationLabelSchemaStatus {

	ACTIVE, RETIRED

}
