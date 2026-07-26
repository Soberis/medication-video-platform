package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "annotation_task")
class AnnotationTask {

	@Id
	@Column(name = "annotation_task_id", nullable = false, updatable = false)
	private UUID annotationTaskId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "label_schema_id", nullable = false, updatable = false)
	private UUID labelSchemaId;

	@Column(name = "label_schema_digest", nullable = false, updatable = false, length = 64)
	private String labelSchemaDigest;

	@Column(name = "assignee_actor_id", nullable = false, length = 128)
	private String assigneeActorId;

	@Column(name = "created_by", nullable = false, updatable = false, length = 128)
	private String createdBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "task_status", nullable = false, length = 32)
	private AnnotationTaskStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected AnnotationTask() {
	}

	private AnnotationTask(
			UUID projectId,
			UUID clipAssetId,
			String assigneeActorId,
			String createdBy,
			String idempotencyKey,
			AnnotationLabelSchemaBinding labelSchema,
			Instant now) {
		this.annotationTaskId = UUID.randomUUID();
		this.projectId = projectId;
		this.clipAssetId = clipAssetId;
		this.labelSchemaId = labelSchema.labelSchemaId();
		this.labelSchemaDigest = labelSchema.schemaDigest();
		this.assigneeActorId = assigneeActorId;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.status = AnnotationTaskStatus.ASSIGNED;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static AnnotationTask create(
			UUID projectId,
			UUID clipAssetId,
			String assigneeActorId,
			String createdBy,
			String idempotencyKey,
			AnnotationLabelSchemaBinding labelSchema,
			Instant now) {
		return new AnnotationTask(
				projectId,
				clipAssetId,
				assigneeActorId,
				createdBy,
				idempotencyKey,
				labelSchema,
				now);
	}

	boolean matchesCreationRequest(UUID clipAssetId, String assigneeActorId) {
		return this.clipAssetId.equals(clipAssetId) && this.assigneeActorId.equals(assigneeActorId);
	}

	UUID getAnnotationTaskId() {
		return this.annotationTaskId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	UUID getLabelSchemaId() {
		return this.labelSchemaId;
	}

	String getLabelSchemaDigest() {
		return this.labelSchemaDigest;
	}

	String getAssigneeActorId() {
		return this.assigneeActorId;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	AnnotationTaskStatus getStatus() {
		return this.status;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

	long getVersion() {
		return this.version;
	}

	void submit(Instant now) {
		if (this.status != AnnotationTaskStatus.ASSIGNED) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_ALREADY_SUBMITTED",
					"The annotation task has already been submitted.",
					409);
		}
		this.status = AnnotationTaskStatus.SUBMITTED;
		this.updatedAt = now;
	}

	void returnForRework(Instant now) {
		if (this.status != AnnotationTaskStatus.SUBMITTED) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_NOT_SUBMITTED",
					"Only a submitted annotation task can be returned for rework.",
					409);
		}
		this.status = AnnotationTaskStatus.ASSIGNED;
		this.updatedAt = now;
	}
}

enum AnnotationTaskStatus {
	ASSIGNED,
	SUBMITTED
}
