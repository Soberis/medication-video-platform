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
@Table(name = "upload_part")
class UploadPart {

	@Id
	@Column(name = "upload_part_id", nullable = false, updatable = false)
	private UUID uploadPartId;

	@Column(name = "upload_file_id", nullable = false, updatable = false)
	private UUID uploadFileId;

	@Column(name = "part_number", nullable = false, updatable = false)
	private int partNumber;

	@Column(name = "expected_size_bytes", nullable = false, updatable = false)
	private long expectedSizeBytes;

	@Column(name = "sha256", nullable = false, length = 64, updatable = false)
	private String sha256;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private UploadPartStatus status;

	@Column(name = "authorization_expires_at", nullable = false)
	private Instant authorizationExpiresAt;

	@Column(name = "storage_etag", length = 2048)
	private String storageEtag;

	@Column(name = "uploaded_size_bytes")
	private Long uploadedSizeBytes;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected UploadPart() {
	}

	private UploadPart(
			UUID uploadFileId,
			int partNumber,
			long expectedSizeBytes,
			String sha256,
			Instant authorizationExpiresAt,
			Instant createdAt) {
		this.uploadPartId = UUID.randomUUID();
		this.uploadFileId = uploadFileId;
		this.partNumber = partNumber;
		this.expectedSizeBytes = expectedSizeBytes;
		this.sha256 = sha256;
		this.status = UploadPartStatus.AUTHORIZED;
		this.authorizationExpiresAt = authorizationExpiresAt;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static UploadPart authorize(
			UUID uploadFileId,
			int partNumber,
			long expectedSizeBytes,
			String sha256,
			Instant authorizationExpiresAt,
			Instant createdAt) {
		return new UploadPart(
				uploadFileId,
				partNumber,
				expectedSizeBytes,
				sha256,
				authorizationExpiresAt,
				createdAt);
	}

	void reauthorize(Instant authorizationExpiresAt, Instant changedAt) {
		if (this.status != UploadPartStatus.AUTHORIZED) {
			throw new IllegalStateException("A confirmed upload part cannot be reauthorized.");
		}
		this.authorizationExpiresAt = authorizationExpiresAt;
		this.updatedAt = changedAt;
	}

	void confirm(MultipartObjectStorage.StoredPart storedPart, Instant changedAt) {
		if (storedPart.partNumber() != this.partNumber) {
			throw new IllegalArgumentException("Stored part number does not match the upload part.");
		}
		if (storedPart.sizeBytes() != this.expectedSizeBytes) {
			throw new UploadMultipartException(
					"UPLOAD_PART_SIZE_MISMATCH",
					"The stored upload part size does not match the immutable file manifest.");
		}
		if (this.status == UploadPartStatus.UPLOADED) {
			if (!this.storageEtag.equals(storedPart.eTag())
					|| !this.uploadedSizeBytes.equals(storedPart.sizeBytes())) {
				throw new UploadMultipartException(
						"UPLOAD_PART_STORAGE_CONFLICT",
						"The stored upload part changed after it was confirmed.");
			}
			return;
		}
		if (this.status != UploadPartStatus.AUTHORIZED) {
			throw new IllegalStateException("Upload part cannot be confirmed from its current state.");
		}
		this.status = UploadPartStatus.UPLOADED;
		this.storageEtag = storedPart.eTag();
		this.uploadedSizeBytes = storedPart.sizeBytes();
		this.confirmedAt = changedAt;
		this.updatedAt = changedAt;
	}

	UUID getUploadFileId() {
		return this.uploadFileId;
	}

	int getPartNumber() {
		return this.partNumber;
	}

	long getExpectedSizeBytes() {
		return this.expectedSizeBytes;
	}

	String getSha256() {
		return this.sha256;
	}

	UploadPartStatus getStatus() {
		return this.status;
	}

	Instant getAuthorizationExpiresAt() {
		return this.authorizationExpiresAt;
	}

	String getStorageEtag() {
		return this.storageEtag;
	}

	Long getUploadedSizeBytes() {
		return this.uploadedSizeBytes;
	}

	Instant getConfirmedAt() {
		return this.confirmedAt;
	}
}
