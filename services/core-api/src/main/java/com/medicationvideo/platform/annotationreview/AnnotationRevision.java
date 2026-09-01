package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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

	@Column(name = "capture_group_id", nullable = false, updatable = false)
	private UUID captureGroupId;

	@Column(name = "primary_clip_asset_id", nullable = false, updatable = false)
	private UUID primaryClipAssetId;

	@Column(name = "primary_camera_number", nullable = false, updatable = false)
	private int primaryCameraNumber;

	@Column(name = "revision_number", nullable = false, updatable = false)
	private int revisionNumber;

	@Column(name = "annotation_schema_version", nullable = false, updatable = false, length = 64)
	private String annotationSchemaVersion;

	@Column(name = "label_schema_id", nullable = false, updatable = false)
	private UUID labelSchemaId;

	@Column(name = "label_schema_digest", nullable = false, updatable = false, length = 64)
	private String labelSchemaDigest;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "annotation_document", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> annotationDocument;

	@Column(name = "document_sha256", nullable = false, updatable = false, length = 64)
	private String documentSha256;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "confirmed_view_set", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> confirmedViewSet;

	@Column(name = "input_version", nullable = false, updatable = false, length = 128)
	private String inputVersion;

	@Column(name = "submitted_by", nullable = false, updatable = false, length = 128)
	private String submittedBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "submitted_at", nullable = false, updatable = false)
	private Instant submittedAt;

	protected AnnotationRevision() {
	}

	private AnnotationRevision(UUID projectId, UUID annotationTaskId, UUID clipAssetId, UUID captureGroupId,
			UUID primaryClipAssetId, int primaryCameraNumber, int revisionNumber, UUID labelSchemaId,
			String labelSchemaDigest, Map<String, Object> annotationDocument, String documentSha256,
			Map<String, Object> confirmedViewSet, String inputVersion, String submittedBy, String idempotencyKey,
			Instant submittedAt) {
		this.annotationRevisionId = UUID.randomUUID();
		this.projectId = projectId;
		this.annotationTaskId = annotationTaskId;
		this.clipAssetId = clipAssetId;
		this.captureGroupId = Objects.requireNonNull(captureGroupId, "captureGroupId");
		this.primaryClipAssetId = Objects.requireNonNull(primaryClipAssetId, "primaryClipAssetId");
		this.primaryCameraNumber = primaryCameraNumber;
		this.revisionNumber = revisionNumber;
		this.annotationSchemaVersion = "annotation-document.v1";
		this.labelSchemaId = labelSchemaId;
		this.labelSchemaDigest = labelSchemaDigest;
		this.annotationDocument = Map.copyOf(annotationDocument);
		this.documentSha256 = documentSha256;
		this.confirmedViewSet = Map.copyOf(Objects.requireNonNull(confirmedViewSet, "confirmedViewSet"));
		this.inputVersion = Objects.requireNonNull(inputVersion, "inputVersion");
		this.submittedBy = submittedBy;
		this.idempotencyKey = idempotencyKey;
		this.submittedAt = submittedAt;
	}

	static AnnotationRevision submitForCaptureGroup(AnnotationTask task, int revisionNumber,
			Map<String, Object> annotationDocument, String documentSha256, String submittedBy, String idempotencyKey,
			Instant submittedAt, Map<String, Object> confirmedViewSet, String inputVersion) {
		return new AnnotationRevision(task.getProjectId(), task.getAnnotationTaskId(), task.getPrimaryClipAssetId(),
				task.getCaptureGroupId(), task.getPrimaryClipAssetId(), task.getPrimaryCameraNumber(), revisionNumber,
				task.getLabelSchemaId(), task.getLabelSchemaDigest(), annotationDocument, documentSha256,
				confirmedViewSet, inputVersion, submittedBy, idempotencyKey, submittedAt);
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

	UUID getCaptureGroupId() {
		return this.captureGroupId;
	}

	UUID getPrimaryClipAssetId() {
		return this.primaryClipAssetId;
	}

	int getPrimaryCameraNumber() {
		return this.primaryCameraNumber;
	}

	int getRevisionNumber() {
		return this.revisionNumber;
	}

	String getAnnotationSchemaVersion() {
		return this.annotationSchemaVersion;
	}

	UUID getLabelSchemaId() {
		return this.labelSchemaId;
	}

	String getLabelSchemaDigest() {
		return this.labelSchemaDigest;
	}

	String getDocumentSha256() {
		return this.documentSha256;
	}

	Map<String, Object> getConfirmedViewSet() {
		return this.confirmedViewSet;
	}

	String getInputVersion() {
		return this.inputVersion;
	}

	Map<String, Object> getAnnotationDocument() {
		return this.annotationDocument;
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
