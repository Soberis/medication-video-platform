package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
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
@Table(name = "annotation_task_view")
class AnnotationTaskView {

	@Id
	@Column(name = "annotation_task_view_id", nullable = false, updatable = false)
	private UUID annotationTaskViewId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "annotation_task_id", nullable = false, updatable = false)
	private UUID annotationTaskId;

	@Column(name = "camera_number", nullable = false, updatable = false)
	private int cameraNumber;

	@Column(name = "camera_stream_id")
	private UUID cameraStreamId;

	@Column(name = "clip_asset_id")
	private UUID clipAssetId;

	@Column(name = "camera_stream_version", nullable = false)
	private long cameraStreamVersion;

	@Column(name = "clip_asset_version", nullable = false)
	private long clipAssetVersion;

	@Column(name = "source_asset_sha256", length = 64)
	private String sourceAssetSha256;

	@Column(name = "media_status", nullable = false, length = 32)
	private String mediaStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "view_state", nullable = false, length = 16)
	private AnnotationTaskViewState state;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "draft_document", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> draftDocument;

	@Column(name = "exclusion_reason", length = 1000)
	private String exclusionReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected AnnotationTaskView() {
	}

	private AnnotationTaskView(AnnotationTask task, int cameraNumber, UUID cameraStreamId, UUID clipAssetId,
			long cameraStreamVersion, long clipAssetVersion, String sourceAssetSha256, String mediaStatus,
			Instant now) {
		this.annotationTaskViewId = UUID.randomUUID();
		this.projectId = task.getProjectId();
		this.annotationTaskId = task.getAnnotationTaskId();
		this.cameraNumber = cameraNumber;
		this.cameraStreamId = cameraStreamId;
		this.clipAssetId = clipAssetId;
		this.cameraStreamVersion = cameraStreamVersion;
		this.clipAssetVersion = clipAssetVersion;
		this.sourceAssetSha256 = sourceAssetSha256;
		this.mediaStatus = Objects.requireNonNull(mediaStatus, "mediaStatus");
		this.state = AnnotationTaskViewState.PENDING;
		this.draftDocument = emptyDraft();
		this.createdAt = now;
		this.updatedAt = now;
	}

	static AnnotationTaskView create(AnnotationTask task, int cameraNumber, UUID cameraStreamId, UUID clipAssetId,
			long cameraStreamVersion, long clipAssetVersion, String sourceAssetSha256, String mediaStatus,
			Instant now) {
		return new AnnotationTaskView(task, cameraNumber, cameraStreamId, clipAssetId, cameraStreamVersion,
				clipAssetVersion, sourceAssetSha256, mediaStatus, now);
	}

	void update(AnnotationTaskViewState state, Map<String, Object> draftDocument, String exclusionReason, Instant now) {
		this.state = Objects.requireNonNull(state, "state");
		this.draftDocument = immutableJson(draftDocument);
		this.exclusionReason = exclusionReason == null || exclusionReason.isBlank() ? null : exclusionReason.trim();
		this.updatedAt = now;
	}

	void refreshSource(UUID cameraStreamId, UUID clipAssetId, long cameraStreamVersion, long clipAssetVersion,
			String sourceAssetSha256, String mediaStatus, Instant now) {
		this.cameraStreamId = cameraStreamId;
		this.clipAssetId = clipAssetId;
		this.cameraStreamVersion = cameraStreamVersion;
		this.clipAssetVersion = clipAssetVersion;
		this.sourceAssetSha256 = sourceAssetSha256;
		this.mediaStatus = Objects.requireNonNull(mediaStatus, "mediaStatus");
		this.state = AnnotationTaskViewState.PENDING;
		this.draftDocument = emptyDraft();
		this.exclusionReason = null;
		this.updatedAt = now;
	}

	static Map<String, Object> emptyDraft() {
		return Map.of("segments", java.util.List.of(), "note", "");
	}

	private static Map<String, Object> immutableJson(Map<String, Object> value) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(value, "draftDocument")));
	}

	UUID getAnnotationTaskViewId() {
		return this.annotationTaskViewId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getAnnotationTaskId() {
		return this.annotationTaskId;
	}

	int getCameraNumber() {
		return this.cameraNumber;
	}

	UUID getCameraStreamId() {
		return this.cameraStreamId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	long getCameraStreamVersion() {
		return this.cameraStreamVersion;
	}

	long getClipAssetVersion() {
		return this.clipAssetVersion;
	}

	String getSourceAssetSha256() {
		return this.sourceAssetSha256;
	}

	String getMediaStatus() {
		return this.mediaStatus;
	}

	AnnotationTaskViewState getState() {
		return this.state;
	}

	Map<String, Object> getDraftDocument() {
		return this.draftDocument;
	}

	String getExclusionReason() {
		return this.exclusionReason;
	}

	long getVersion() {
		return this.version;
	}

}

enum AnnotationTaskViewState {

	PENDING, DRAFT, SUBMITTED, EXCLUDED

}
