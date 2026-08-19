package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "capture_event_log")
class CaptureEventLog {

	@Id
	@Column(name = "capture_event_log_id", nullable = false, updatable = false)
	private UUID captureEventLogId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "batch_id", nullable = false, updatable = false)
	private UUID batchId;

	@Column(name = "source_upload_file_id", nullable = false, updatable = false)
	private UUID sourceUploadFileId;

	@Column(name = "client_file_id", nullable = false, updatable = false)
	private UUID clientFileId;

	@Column(name = "device_id", nullable = false, length = 128, updatable = false)
	private String deviceId;

	@Column(name = "content_type", nullable = false, length = 128, updatable = false)
	private String contentType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private long sizeBytes;

	@Column(name = "sha256", nullable = false, length = 64, updatable = false)
	private String sha256;

	@Column(name = "storage_bucket", nullable = false, length = 255, updatable = false)
	private String storageBucket;

	@Column(name = "object_key", nullable = false, length = 1024, updatable = false)
	private String objectKey;

	@Column(name = "storage_etag", nullable = false, length = 2048, updatable = false)
	private String storageEtag;

	@Column(name = "indexed_at", nullable = false, updatable = false)
	private Instant indexedAt;

	protected CaptureEventLog() {
	}

	private CaptureEventLog(UploadBatch batch, UploadFile file, Instant indexedAt) {
		this.captureEventLogId = UUID.randomUUID();
		this.projectId = batch.getProjectId();
		this.batchId = batch.getBatchId();
		this.sourceUploadFileId = file.getUploadFileId();
		this.clientFileId = file.getClientFileId();
		this.deviceId = batch.getDeviceId();
		this.contentType = file.getContentType();
		this.sizeBytes = file.getSizeBytes();
		this.sha256 = file.getVerifiedSha256();
		this.storageBucket = file.getStorageBucket();
		this.objectKey = file.getObjectKey();
		this.storageEtag = file.getCompletedStorageEtag();
		this.indexedAt = indexedAt;
	}

	static CaptureEventLog fromUploadedFile(UploadBatch batch, UploadFile file, Instant indexedAt) {
		if (file.getFileKind() != UploadFileKind.EVENT_LOG || file.getStatus() != UploadFileStatus.UPLOADED) {
			throw new IllegalArgumentException("Only a verified uploaded event log can be indexed.");
		}
		return new CaptureEventLog(batch, file, indexedAt);
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getBatchId() {
		return this.batchId;
	}

	UUID getSourceUploadFileId() {
		return this.sourceUploadFileId;
	}

	UUID getClientFileId() {
		return this.clientFileId;
	}

	String getDeviceId() {
		return this.deviceId;
	}

	String getContentType() {
		return this.contentType;
	}

	long getSizeBytes() {
		return this.sizeBytes;
	}

	String getSha256() {
		return this.sha256;
	}

	Instant getIndexedAt() {
		return this.indexedAt;
	}

}
