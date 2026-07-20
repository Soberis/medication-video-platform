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
@Table(name = "clip_asset")
class ClipAsset {

	@Id
	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "batch_id", nullable = false, updatable = false)
	private UUID batchId;

	@Column(name = "source_upload_file_id", nullable = false, updatable = false)
	private UUID sourceUploadFileId;

	@Column(name = "client_file_id", nullable = false, updatable = false)
	private UUID clientFileId;

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

	@Column(name = "folder_id")
	private UUID folderId;

	@Enumerated(EnumType.STRING)
	@Column(name = "asset_status", nullable = false, length = 32)
	private ClipAssetStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected ClipAsset() {
	}

	private ClipAsset(UploadBatch batch, UploadFile file, Instant createdAt) {
		this.clipAssetId = UUID.randomUUID();
		this.projectId = batch.getProjectId();
		this.batchId = batch.getBatchId();
		this.sourceUploadFileId = file.getUploadFileId();
		this.clientFileId = file.getClientFileId();
		this.contentType = file.getContentType();
		this.sizeBytes = file.getSizeBytes();
		this.sha256 = file.getVerifiedSha256();
		this.storageBucket = file.getStorageBucket();
		this.objectKey = file.getObjectKey();
		this.storageEtag = file.getCompletedStorageEtag();
		this.folderId = batch.getAssetFolderId();
		this.status = ClipAssetStatus.UPLOADED;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static ClipAsset fromUploadedVideo(UploadBatch batch, UploadFile file, Instant createdAt) {
		if (file.getFileKind() != UploadFileKind.VIDEO || file.getStatus() != UploadFileStatus.UPLOADED) {
			throw new IllegalArgumentException("Only a verified uploaded video can create a clip asset.");
		}
		return new ClipAsset(batch, file, createdAt);
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
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

	String getContentType() {
		return this.contentType;
	}

	long getSizeBytes() {
		return this.sizeBytes;
	}

	String getSha256() {
		return this.sha256;
	}

	UUID getFolderId() {
		return this.folderId;
	}

	ClipAssetStatus getStatus() {
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

	MultipartObjectStorage.ObjectReference getObjectReference() {
		return new MultipartObjectStorage.ObjectReference(this.storageBucket, this.objectKey);
	}

	void beginMediaIngest(Instant changedAt) {
		if (this.status == ClipAssetStatus.INGESTING) {
			return;
		}
		if (this.status != ClipAssetStatus.UPLOADED) {
			throw new ClipAssetMediaIngestException(
					"CLIP_ASSET_NOT_PROBEABLE",
					"The clip asset is not available for media ingest.",
					false);
		}
		this.status = ClipAssetStatus.INGESTING;
		this.updatedAt = changedAt;
	}

	void completeMediaIngest(boolean succeeded, Instant changedAt) {
		if (this.status != ClipAssetStatus.INGESTING) {
			throw new ClipAssetMediaIngestException(
					"CLIP_ASSET_INGEST_STATE_CONFLICT",
					"The clip asset is not currently ingesting.",
					false);
		}
		this.status = succeeded ? ClipAssetStatus.INGESTED : ClipAssetStatus.INGEST_FAILED;
		this.updatedAt = changedAt;
	}

	void classify(UUID newFolderId, Instant changedAt) {
		this.folderId = newFolderId;
		this.updatedAt = changedAt;
	}
}
