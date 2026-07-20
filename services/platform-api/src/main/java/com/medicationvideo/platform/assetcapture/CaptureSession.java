package com.medicationvideo.platform.assetcapture;

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
@Table(name = "capture_session")
class CaptureSession {

	@Id
	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "batch_id", nullable = false, updatable = false)
	private UUID batchId;

	@Column(name = "device_id", nullable = false, length = 128, updatable = false)
	private String deviceId;

	@Column(name = "camera_set_id", nullable = false, updatable = false)
	private UUID cameraSetId;

	@Column(name = "metadata_upload_file_id", updatable = false)
	private UUID metadataUploadFileId;

	@Column(name = "metadata_schema_version", length = 64, updatable = false)
	private String metadataSchemaVersion;

	@Column(name = "metadata_sha256", length = 64, updatable = false)
	private String metadataSha256;

	@Column(name = "expected_camera_count", nullable = false, updatable = false)
	private int expectedCameraCount;

	@Column(name = "received_camera_count", nullable = false)
	private int receivedCameraCount;

	@Column(name = "sync_group_id", length = 128, updatable = false)
	private String syncGroupId;

	@Column(name = "device_time_utc", updatable = false)
	private Instant deviceTimeUtc;

	@Column(name = "device_timezone", length = 64, updatable = false)
	private String deviceTimezone;

	@Column(name = "server_receive_time", nullable = false, updatable = false)
	private Instant serverReceiveTime;

	@Enumerated(EnumType.STRING)
	@Column(name = "assembly_status", nullable = false, length = 32)
	private CaptureSessionAssemblyStatus assemblyStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "quality_status", nullable = false, length = 32)
	private CaptureQualityStatus qualityStatus;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CaptureSession() {
	}

	private CaptureSession(
			UUID projectId,
			UUID batchId,
			UploadFile metadataFile,
			CaptureSessionMetadataV1 metadata,
			int receivedCameraCount,
			Instant createdAt) {
		this.captureSessionId = metadata.captureSessionId();
		this.projectId = projectId;
		this.batchId = batchId;
		this.deviceId = metadata.deviceId();
		this.cameraSetId = metadata.cameraSetId();
		this.metadataUploadFileId = metadataFile.getUploadFileId();
		this.metadataSchemaVersion = metadata.schemaVersion();
		this.metadataSha256 = metadataFile.getVerifiedSha256();
		this.expectedCameraCount = metadata.expectedCameraCount();
		this.receivedCameraCount = receivedCameraCount;
		this.syncGroupId = metadata.syncGroupId();
		this.deviceTimeUtc = metadata.deviceTimeUtc();
		this.deviceTimezone = metadata.deviceTimezone();
		this.serverReceiveTime = createdAt;
		this.assemblyStatus = receivedCameraCount == this.expectedCameraCount
				? CaptureSessionAssemblyStatus.COMPLETE
				: CaptureSessionAssemblyStatus.ASSEMBLING;
		this.qualityStatus = CaptureQualityStatus.PENDING;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static CaptureSession assemble(
			UUID projectId,
			UUID batchId,
			UploadFile metadataFile,
			CaptureSessionMetadataV1 metadata,
			int receivedCameraCount,
			Instant createdAt) {
		return new CaptureSession(
				projectId,
				batchId,
				metadataFile,
				metadata,
				receivedCameraCount,
				createdAt);
	}

	static CaptureSession importLocal(
			UUID projectId,
			UploadBatch batch,
			CameraSetConfiguration cameraSet,
			Instant createdAt) {
		var session = new CaptureSession(
				projectId,
				batch.getBatchId(),
				cameraSet,
				createdAt);
		return session;
	}

	private CaptureSession(
			UUID projectId,
			UUID batchId,
			CameraSetConfiguration cameraSet,
			Instant createdAt) {
		this.captureSessionId = UUID.randomUUID();
		this.projectId = projectId;
		this.batchId = batchId;
		this.deviceId = "PLATFORM_LOCAL";
		this.cameraSetId = cameraSet.getCameraSetId();
		this.metadataSchemaVersion = "platform-local-upload-v1";
		this.expectedCameraCount = 1;
		this.receivedCameraCount = 1;
		this.serverReceiveTime = createdAt;
		this.assemblyStatus = CaptureSessionAssemblyStatus.COMPLETE;
		this.qualityStatus = CaptureQualityStatus.PENDING;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	UUID getCaptureSessionId() {
		return this.captureSessionId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getBatchId() {
		return this.batchId;
	}

	String getDeviceId() {
		return this.deviceId;
	}

	UUID getCameraSetId() {
		return this.cameraSetId;
	}

	UUID getMetadataUploadFileId() {
		return this.metadataUploadFileId;
	}

	String getMetadataSchemaVersion() {
		return this.metadataSchemaVersion;
	}

	String getMetadataSha256() {
		return this.metadataSha256;
	}

	int getExpectedCameraCount() {
		return this.expectedCameraCount;
	}

	int getReceivedCameraCount() {
		return this.receivedCameraCount;
	}

	String getSyncGroupId() {
		return this.syncGroupId;
	}

	Instant getDeviceTimeUtc() {
		return this.deviceTimeUtc;
	}

	String getDeviceTimezone() {
		return this.deviceTimezone;
	}

	Instant getServerReceiveTime() {
		return this.serverReceiveTime;
	}

	CaptureSessionAssemblyStatus getAssemblyStatus() {
		return this.assemblyStatus;
	}

	CaptureQualityStatus getQualityStatus() {
		return this.qualityStatus;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}
}
