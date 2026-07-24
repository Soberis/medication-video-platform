package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "annotation_revision")
class AnnotationRevision {

	@Id
	@Column(name = "annotation_revision_id", nullable = false, updatable = false)
	private UUID annotationRevisionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "annotation_task_id", nullable = false, updatable = false)
	private UUID annotationTaskId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "revision_number", nullable = false, updatable = false)
	private int revisionNumber;

	@Column(name = "annotation_schema_version", nullable = false, updatable = false, length = 64)
	private String annotationSchemaVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "annotation_document", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> annotationDocument;

	@Column(name = "document_sha256", nullable = false, updatable = false, length = 64)
	private String documentSha256;

	@Column(name = "submitted_by", nullable = false, updatable = false, length = 128)
	private String submittedBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "submitted_at", nullable = false, updatable = false)
	private Instant submittedAt;

	protected AnnotationRevision() {
	}

	private AnnotationRevision(
			UUID projectId,
			UUID annotationTaskId,
			UUID clipAssetId,
			Map<String, Object> annotationDocument,
			String documentSha256,
			String submittedBy,
			String idempotencyKey,
			Instant submittedAt) {
		this.annotationRevisionId = UUID.randomUUID();
		this.projectId = projectId;
		this.annotationTaskId = annotationTaskId;
		this.clipAssetId = clipAssetId;
		this.revisionNumber = 1;
		this.annotationSchemaVersion = "annotation-document.v1";
		this.annotationDocument = Map.copyOf(annotationDocument);
		this.documentSha256 = documentSha256;
		this.submittedBy = submittedBy;
		this.idempotencyKey = idempotencyKey;
		this.submittedAt = submittedAt;
	}

	static AnnotationRevision submit(
			AnnotationTask task,
			Map<String, Object> annotationDocument,
			String documentSha256,
			String submittedBy,
			String idempotencyKey,
			Instant submittedAt) {
		return new AnnotationRevision(
				task.getProjectId(),
				task.getAnnotationTaskId(),
				task.getClipAssetId(),
				annotationDocument,
				documentSha256,
				submittedBy,
				idempotencyKey,
				submittedAt);
	}

	UUID getAnnotationRevisionId() {
		return this.annotationRevisionId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getAnnotationTaskId() {
		return this.annotationTaskId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	int getRevisionNumber() {
		return this.revisionNumber;
	}

	String getAnnotationSchemaVersion() {
		return this.annotationSchemaVersion;
	}

	String getDocumentSha256() {
		return this.documentSha256;
	}

	String getSubmittedBy() {
		return this.submittedBy;
	}

	Instant getSubmittedAt() {
		return this.submittedAt;
	}

	boolean matchesSubmission(String documentSha256) {
		return this.documentSha256.equals(documentSha256);
	}
}
