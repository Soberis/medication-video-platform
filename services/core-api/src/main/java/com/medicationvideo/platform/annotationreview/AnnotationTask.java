package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

	@Column(name = "clip_asset_id", updatable = false)
	private UUID clipAssetId;

	@Column(name = "capture_group_id", updatable = false)
	private UUID captureGroupId;

	@Column(name = "primary_clip_asset_id")
	private UUID primaryClipAssetId;

	@Column(name = "primary_camera_number")
	private Integer primaryCameraNumber;

	@Column(name = "capture_group_quality_review_id")
	private UUID captureGroupQualityReviewId;

	@Column(name = "group_quality_revision")
	private Long groupQualityRevision;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "confirmed_view_set", columnDefinition = "jsonb")
	private Map<String, Object> confirmedViewSet;

	@Column(name = "group_input_version", length = 128)
	private String groupInputVersion;

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

	private AnnotationTask(UUID projectId, UUID clipAssetId, String assigneeActorId, String createdBy,
			String idempotencyKey, AnnotationLabelSchemaBinding labelSchema, Instant now) {
		this(projectId, clipAssetId, null, null, null, null, null, null, null, assigneeActorId, createdBy,
				idempotencyKey, labelSchema, now);
	}

	private AnnotationTask(UUID projectId, UUID clipAssetId, UUID captureGroupId, UUID primaryClipAssetId,
			Integer primaryCameraNumber, UUID captureGroupQualityReviewId, Long groupQualityRevision,
			Map<String, Object> confirmedViewSet, String groupInputVersion, String assigneeActorId, String createdBy,
			String idempotencyKey, AnnotationLabelSchemaBinding labelSchema, Instant now) {
		this.annotationTaskId = UUID.randomUUID();
		this.projectId = projectId;
		this.clipAssetId = clipAssetId;
		this.captureGroupId = captureGroupId;
		this.primaryClipAssetId = primaryClipAssetId;
		this.primaryCameraNumber = primaryCameraNumber;
		this.captureGroupQualityReviewId = captureGroupQualityReviewId;
		this.groupQualityRevision = groupQualityRevision;
		this.confirmedViewSet = confirmedViewSet == null ? null : Map.copyOf(confirmedViewSet);
		this.groupInputVersion = groupInputVersion;
		this.labelSchemaId = labelSchema.labelSchemaId();
		this.labelSchemaDigest = labelSchema.schemaDigest();
		this.assigneeActorId = assigneeActorId;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.status = AnnotationTaskStatus.ASSIGNED;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static AnnotationTask create(UUID projectId, UUID clipAssetId, String assigneeActorId, String createdBy,
			String idempotencyKey, AnnotationLabelSchemaBinding labelSchema, Instant now) {
		return new AnnotationTask(projectId, clipAssetId, assigneeActorId, createdBy, idempotencyKey, labelSchema, now);
	}

	static AnnotationTask createForCaptureGroup(UUID projectId, UUID captureGroupId, String assigneeActorId,
			String createdBy, String idempotencyKey, AnnotationLabelSchemaBinding labelSchema,
			UUID captureGroupQualityReviewId, long groupQualityRevision, Map<String, Object> confirmedViewSet,
			String groupInputVersion, Instant now) {
		return new AnnotationTask(projectId, null, captureGroupId, null, null, captureGroupQualityReviewId,
				groupQualityRevision, confirmedViewSet, groupInputVersion, assigneeActorId, createdBy, idempotencyKey,
				labelSchema, now);
	}

	boolean matchesCreationRequest(UUID clipAssetId, String assigneeActorId) {
		return Objects.equals(this.clipAssetId, clipAssetId) && this.assigneeActorId.equals(assigneeActorId);
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

	UUID getCaptureGroupId() {
		return this.captureGroupId;
	}

	UUID getPrimaryClipAssetId() {
		return this.primaryClipAssetId;
	}

	Integer getPrimaryCameraNumber() {
		return this.primaryCameraNumber;
	}

	UUID getCaptureGroupQualityReviewId() {
		return this.captureGroupQualityReviewId;
	}

	Long getGroupQualityRevision() {
		return this.groupQualityRevision;
	}

	Map<String, Object> getConfirmedViewSet() {
		return this.confirmedViewSet;
	}

	String getGroupInputVersion() {
		return this.groupInputVersion;
	}

	boolean isCaptureGroupTask() {
		return this.captureGroupId != null;
	}

	UUID getAnnotationTimebaseClipAssetId() {
		return this.isCaptureGroupTask() ? this.primaryClipAssetId : this.clipAssetId;
	}

	boolean hasPrimaryView() {
		return this.primaryClipAssetId != null && this.primaryCameraNumber != null;
	}

	void selectPrimaryView(UUID clipAssetId, int cameraNumber, Instant now) {
		if (!this.isCaptureGroupTask()) {
			throw new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_NOT_SUPPORTED",
					"A primary view can only be selected for a CaptureGroup annotation task.", 409);
		}
		this.primaryClipAssetId = clipAssetId;
		this.primaryCameraNumber = cameraNumber;
		this.updatedAt = now;
	}

	void refreshGroupQualitySnapshot(UUID captureGroupQualityReviewId, long groupQualityRevision,
			Map<String, Object> confirmedViewSet, String groupInputVersion, Instant now) {
		if (!this.isCaptureGroupTask()) {
			return;
		}
		if (Objects.equals(this.captureGroupQualityReviewId, captureGroupQualityReviewId)
				&& Objects.equals(this.groupQualityRevision, groupQualityRevision)
				&& Objects.equals(this.confirmedViewSet, confirmedViewSet)
				&& Objects.equals(this.groupInputVersion, groupInputVersion)) {
			return;
		}
		this.captureGroupQualityReviewId = captureGroupQualityReviewId;
		this.groupQualityRevision = groupQualityRevision;
		this.confirmedViewSet = Map.copyOf(confirmedViewSet);
		this.groupInputVersion = groupInputVersion;
		this.updatedAt = now;
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
			throw new AnnotationTaskException("ANNOTATION_TASK_ALREADY_SUBMITTED",
					"The annotation task has already been submitted.", 409);
		}
		this.status = AnnotationTaskStatus.SUBMITTED;
		this.updatedAt = now;
	}

	void returnForRework(Instant now) {
		if (this.status != AnnotationTaskStatus.SUBMITTED) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NOT_SUBMITTED",
					"Only a submitted annotation task can be returned for rework.", 409);
		}
		this.status = AnnotationTaskStatus.ASSIGNED;
		this.updatedAt = now;
	}

	void complete(Instant now) {
		if (this.status != AnnotationTaskStatus.SUBMITTED) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NOT_SUBMITTED",
					"Only a submitted annotation task can be completed.", 409);
		}
		this.status = AnnotationTaskStatus.COMPLETED;
		this.updatedAt = now;
	}

}

enum AnnotationTaskStatus {

	ASSIGNED, SUBMITTED, COMPLETED

}
