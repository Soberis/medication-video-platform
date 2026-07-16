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
@Table(name = "upload_file")
class UploadFile {

	@Id
	@Column(name = "upload_file_id", nullable = false, updatable = false)
	private UUID uploadFileId;

	@Column(name = "batch_id", nullable = false, updatable = false)
	private UUID batchId;

	@Column(name = "client_file_id", nullable = false, updatable = false)
	private UUID clientFileId;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_kind", nullable = false, length = 32, updatable = false)
	private UploadFileKind fileKind;

	@Column(name = "content_type", nullable = false, length = 128, updatable = false)
	private String contentType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private long sizeBytes;

	@Column(name = "sha256", nullable = false, length = 64, updatable = false)
	private String sha256;

	@Column(name = "part_size_bytes", nullable = false, updatable = false)
	private long partSizeBytes;

	@Column(name = "total_parts", nullable = false, updatable = false)
	private int totalParts;

	@Column(name = "storage_bucket", length = 255)
	private String storageBucket;

	@Column(name = "object_key", length = 1024)
	private String objectKey;

	@Column(name = "storage_upload_id", length = 2048)
	private String storageUploadId;

	@Column(name = "multipart_created_at")
	private Instant multipartCreatedAt;

	@Column(name = "completion_attempt_id")
	private UUID completionAttemptId;

	@Column(name = "completion_started_at")
	private Instant completionStartedAt;

	@Column(name = "completion_lease_expires_at")
	private Instant completionLeaseExpiresAt;

	@Column(name = "completion_finished_at")
	private Instant completionFinishedAt;

	@Column(name = "verified_sha256", length = 64)
	private String verifiedSha256;

	@Column(name = "completed_storage_etag", length = 2048)
	private String completedStorageEtag;

	@Column(name = "completion_error_code", length = 64)
	private String completionErrorCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private UploadFileStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected UploadFile() {
	}

	private UploadFile(
			UUID uploadFileId,
			UUID batchId,
			UUID clientFileId,
			String idempotencyKey,
			String requestFingerprint,
			UploadFileKind fileKind,
			String contentType,
			long sizeBytes,
			String sha256,
			long partSizeBytes,
			int totalParts,
			Instant createdAt) {
		this.uploadFileId = uploadFileId;
		this.batchId = batchId;
		this.clientFileId = clientFileId;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.fileKind = fileKind;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.partSizeBytes = partSizeBytes;
		this.totalParts = totalParts;
		this.status = UploadFileStatus.REGISTERED;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static UploadFile register(
			UUID batchId,
			UUID clientFileId,
			String idempotencyKey,
			String requestFingerprint,
			UploadFileRegistrationRequest request,
			int totalParts,
			Instant createdAt) {
		return new UploadFile(
				UUID.randomUUID(),
				batchId,
				clientFileId,
				idempotencyKey,
				requestFingerprint,
				request.fileKind(),
				request.contentType(),
				request.sizeBytes(),
				request.sha256(),
				request.partSizeBytes(),
				totalParts,
				createdAt);
	}

	UUID getUploadFileId() {
		return this.uploadFileId;
	}

	UUID getBatchId() {
		return this.batchId;
	}

	UUID getClientFileId() {
		return this.clientFileId;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	UploadFileKind getFileKind() {
		return this.fileKind;
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

	long getPartSizeBytes() {
		return this.partSizeBytes;
	}

	int getTotalParts() {
		return this.totalParts;
	}

	UploadFileStatus getStatus() {
		return this.status;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	boolean hasMultipartSession() {
		return this.storageUploadId != null;
	}

	MultipartObjectStorage.CreatedUpload getMultipartUpload() {
		if (!hasMultipartSession()) {
			throw new IllegalStateException("Upload file has no multipart session.");
		}
		return new MultipartObjectStorage.CreatedUpload(
				this.storageBucket,
				this.objectKey,
				this.storageUploadId);
	}

	Instant getMultipartCreatedAt() {
		return this.multipartCreatedAt;
	}

	void attachMultipartSession(MultipartObjectStorage.CreatedUpload upload, Instant changedAt) {
		if (this.status != UploadFileStatus.REGISTERED || hasMultipartSession()) {
			throw new IllegalStateException("Upload file already has a multipart session.");
		}
		this.storageBucket = upload.bucket();
		this.objectKey = upload.objectKey();
		this.storageUploadId = upload.uploadId();
		this.multipartCreatedAt = changedAt;
		this.status = UploadFileStatus.SESSION_CREATED;
		this.updatedAt = changedAt;
	}

	void markUploading(Instant changedAt) {
		if (!hasMultipartSession()) {
			throw new IllegalStateException("Upload file has no multipart session.");
		}
		if (this.status == UploadFileStatus.SESSION_CREATED) {
			this.status = UploadFileStatus.UPLOADING;
			this.updatedAt = changedAt;
		}
		else if (this.status != UploadFileStatus.UPLOADING) {
			throw new IllegalStateException("Upload file cannot start uploading from its current state.");
		}
	}

	void startCompletion(UUID attemptId, Instant leaseExpiresAt, Instant changedAt) {
		if (this.status != UploadFileStatus.UPLOADING && this.status != UploadFileStatus.COMPLETING) {
			throw new IllegalStateException("Upload file cannot start completion from its current state.");
		}
		if (!leaseExpiresAt.isAfter(changedAt)) {
			throw new IllegalArgumentException("Completion lease must expire after it starts.");
		}
		this.status = UploadFileStatus.COMPLETING;
		this.completionAttemptId = attemptId;
		this.completionStartedAt = changedAt;
		this.completionLeaseExpiresAt = leaseExpiresAt;
		this.completionFinishedAt = null;
		this.verifiedSha256 = null;
		this.completedStorageEtag = null;
		this.completionErrorCode = null;
		this.updatedAt = changedAt;
	}

	boolean hasActiveCompletionLease(Instant now) {
		return this.status == UploadFileStatus.COMPLETING
				&& this.completionLeaseExpiresAt != null
				&& this.completionLeaseExpiresAt.isAfter(now);
	}

	boolean matchesCompletionAttempt(UUID attemptId) {
		return this.status == UploadFileStatus.COMPLETING && attemptId.equals(this.completionAttemptId);
	}

	void releaseCompletionLease(UUID attemptId, Instant changedAt) {
		requireCompletionAttempt(attemptId);
		this.completionLeaseExpiresAt = changedAt;
		this.updatedAt = changedAt;
	}

	void resumeUploading(UUID attemptId, Instant changedAt) {
		requireCompletionAttempt(attemptId);
		this.status = UploadFileStatus.UPLOADING;
		this.completionAttemptId = null;
		this.completionStartedAt = null;
		this.completionLeaseExpiresAt = null;
		this.updatedAt = changedAt;
	}

	void markUploaded(
			UUID attemptId,
			MultipartObjectStorage.CompletedObject completedObject,
			String observedSha256,
			Instant changedAt) {
		requireCompletionAttempt(attemptId);
		this.status = UploadFileStatus.UPLOADED;
		this.completionAttemptId = null;
		this.completionLeaseExpiresAt = null;
		this.completionFinishedAt = changedAt;
		this.verifiedSha256 = observedSha256;
		this.completedStorageEtag = completedObject.eTag();
		this.completionErrorCode = null;
		this.updatedAt = changedAt;
	}

	void markCompletionFailed(
			UUID attemptId,
			MultipartObjectStorage.CompletedObject completedObject,
			String observedSha256,
			String errorCode,
			Instant changedAt) {
		requireCompletionAttempt(attemptId);
		this.status = UploadFileStatus.FAILED;
		this.completionAttemptId = null;
		this.completionLeaseExpiresAt = null;
		this.completionFinishedAt = changedAt;
		this.verifiedSha256 = observedSha256;
		this.completedStorageEtag = completedObject.eTag();
		this.completionErrorCode = errorCode;
		this.updatedAt = changedAt;
	}

	Instant getCompletionStartedAt() {
		return this.completionStartedAt;
	}

	Instant getCompletionFinishedAt() {
		return this.completionFinishedAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

	String getVerifiedSha256() {
		return this.verifiedSha256;
	}

	String getCompletedStorageEtag() {
		return this.completedStorageEtag;
	}

	String getStorageBucket() {
		return this.storageBucket;
	}

	String getObjectKey() {
		return this.objectKey;
	}

	MultipartObjectStorage.ObjectReference getStorageObject() {
		if (this.storageBucket == null || this.objectKey == null) {
			throw new IllegalStateException("Upload file has no completed storage object reference.");
		}
		return new MultipartObjectStorage.ObjectReference(this.storageBucket, this.objectKey);
	}

	String getCompletionErrorCode() {
		return this.completionErrorCode;
	}

	void requireCompletionAttempt(UUID attemptId) {
		if (this.status != UploadFileStatus.COMPLETING || !attemptId.equals(this.completionAttemptId)) {
			throw new IllegalStateException("Upload file completion attempt is no longer active.");
		}
	}
}
