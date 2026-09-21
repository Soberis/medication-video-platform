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

@Entity
@Table(name = "annotation_task_view_history")
class AnnotationTaskViewHistory {

	@Id
	@Column(name = "annotation_task_view_history_id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "annotation_task_id", nullable = false, updatable = false)
	private UUID annotationTaskId;

	@Column(name = "camera_number", nullable = false, updatable = false)
	private int cameraNumber;

	@Column(name = "camera_stream_id", updatable = false)
	private UUID cameraStreamId;

	@Column(name = "clip_asset_id", updatable = false)
	private UUID clipAssetId;

	@Column(name = "camera_stream_version", nullable = false, updatable = false)
	private long cameraStreamVersion;

	@Column(name = "clip_asset_version", nullable = false, updatable = false)
	private long clipAssetVersion;

	@Column(name = "source_asset_sha256", length = 64, updatable = false)
	private String sourceAssetSha256;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, updatable = false, length = 24)
	private AnnotationTaskViewHistoryAction action;

	@Column(name = "actor_id", nullable = false, updatable = false, length = 128)
	private String actorId;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "view_version", nullable = false, updatable = false)
	private long viewVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "view_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> viewSnapshot;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected AnnotationTaskViewHistory() {
	}

	private AnnotationTaskViewHistory(AnnotationTaskView view, AnnotationTaskViewHistoryAction action, String actorId,
			String idempotencyKey, Map<String, Object> snapshot, Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.projectId = view.getProjectId();
		this.annotationTaskId = view.getAnnotationTaskId();
		this.cameraNumber = view.getCameraNumber();
		this.cameraStreamId = view.getCameraStreamId();
		this.clipAssetId = view.getClipAssetId();
		this.cameraStreamVersion = view.getCameraStreamVersion();
		this.clipAssetVersion = view.getClipAssetVersion();
		this.sourceAssetSha256 = view.getSourceAssetSha256();
		this.action = Objects.requireNonNull(action, "action");
		this.actorId = Objects.requireNonNull(actorId, "actorId");
		this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		this.viewVersion = view.getVersion();
		this.viewSnapshot = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(snapshot));
		this.occurredAt = occurredAt;
	}

	static AnnotationTaskViewHistory record(AnnotationTaskView view, AnnotationTaskViewHistoryAction action,
			String actorId, String idempotencyKey, Map<String, Object> snapshot, Instant occurredAt) {
		return new AnnotationTaskViewHistory(view, action, actorId, idempotencyKey, snapshot, occurredAt);
	}

	Map<String, Object> getViewSnapshot() {
		return this.viewSnapshot;
	}

}

enum AnnotationTaskViewHistoryAction {

	DRAFT_SAVED, SUBMITTED, EXCLUDED, RESTORED, INPUT_CHANGED

}
