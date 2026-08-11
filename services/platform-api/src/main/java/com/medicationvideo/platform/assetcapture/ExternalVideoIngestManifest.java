package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
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
@Table(name = "external_video_ingest_manifest")
class ExternalVideoIngestManifest {

	@Id
	@Column(name = "manifest_id", nullable = false, updatable = false)
	private UUID manifestId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "schema_version", nullable = false, length = 64, updatable = false)
	private String schemaVersion;

	@Column(name = "source_system", nullable = false, length = 128, updatable = false)
	private String sourceSystem;

	@Column(name = "source_device_id", nullable = false, length = 128, updatable = false)
	private String sourceDeviceId;

	@Column(name = "source_batch_id", length = 128, updatable = false)
	private String sourceBatchId;

	@Column(name = "capture_group_key", nullable = false, length = 128, updatable = false)
	private String captureGroupKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "key_source", nullable = false, length = 64, updatable = false)
	private ExternalVideoCaptureGroupKeySource keySource;

	@Column(name = "captured_at", nullable = false, updatable = false)
	private Instant capturedAt;

	@Column(name = "timezone", nullable = false, length = 64, updatable = false)
	private String timezone;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "expected_camera_numbers", nullable = false, columnDefinition = "jsonb", updatable = false)
	private List<Integer> expectedCameraNumbers;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ExternalVideoIngestManifestStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ExternalVideoIngestManifest() {
	}

	private ExternalVideoIngestManifest(UUID projectId, ExternalVideoIngestManifestRequest request, Instant now) {
		this.manifestId = UUID.randomUUID();
		this.projectId = projectId;
		this.schemaVersion = request.schemaVersion();
		this.sourceSystem = request.sourceSystem();
		this.sourceDeviceId = request.sourceDeviceId();
		this.sourceBatchId = request.sourceBatchId();
		this.captureGroupKey = request.captureGroup().captureGroupKey();
		this.keySource = request.captureGroup().keySource();
		this.capturedAt = request.captureGroup().capturedAt();
		this.timezone = request.captureGroup().timezone();
		this.expectedCameraNumbers = request.captureGroup().expectedCameraNumbers().stream().sorted().toList();
		this.status = ExternalVideoIngestManifestStatus.ACCEPTED;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static ExternalVideoIngestManifest accept(UUID projectId, ExternalVideoIngestManifestRequest request, Instant now) {
		return new ExternalVideoIngestManifest(projectId, request, now);
	}

	UUID getManifestId() {
		return this.manifestId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getSchemaVersion() {
		return this.schemaVersion;
	}

	String getSourceSystem() {
		return this.sourceSystem;
	}

	String getSourceDeviceId() {
		return this.sourceDeviceId;
	}

	String getSourceBatchId() {
		return this.sourceBatchId;
	}

	String getCaptureGroupKey() {
		return this.captureGroupKey;
	}

	ExternalVideoCaptureGroupKeySource getKeySource() {
		return this.keySource;
	}

	Instant getCapturedAt() {
		return this.capturedAt;
	}

	String getTimezone() {
		return this.timezone;
	}

	List<Integer> getExpectedCameraNumbers() {
		return this.expectedCameraNumbers;
	}

	ExternalVideoIngestManifestStatus getStatus() {
		return this.status;
	}

	void markBound(Instant now) {
		this.status = ExternalVideoIngestManifestStatus.BOUND;
		this.updatedAt = now;
	}

}

enum ExternalVideoIngestManifestStatus {

	ACCEPTED, BOUND

}
