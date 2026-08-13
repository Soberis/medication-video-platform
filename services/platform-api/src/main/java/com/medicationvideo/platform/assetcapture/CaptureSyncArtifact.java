package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "capture_sync_artifact")
class CaptureSyncArtifact {

	static final String ARTIFACT_ROLE = "SYNCHRONIZED_FRAME_PARQUET";

	static final String SCHEMA_VERSION = "synchronized-frame.v1";

	static final String FILE_NAME = "synchronized-frames.parquet";

	static final String MEDIA_TYPE = "application/vnd.apache.parquet";

	@Id
	@Column(name = "capture_sync_artifact_id", nullable = false, updatable = false)
	private UUID captureSyncArtifactId;

	@Column(name = "capture_sync_revision_id", nullable = false, updatable = false)
	private UUID captureSyncRevisionId;

	@Column(name = "artifact_role", nullable = false, updatable = false, length = 64)
	private String artifactRole;

	@Column(name = "schema_version", nullable = false, updatable = false, length = 64)
	private String schemaVersion;

	@Column(name = "file_name", nullable = false, updatable = false, length = 255)
	private String fileName;

	@Column(name = "media_type", nullable = false, updatable = false, length = 128)
	private String mediaType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private long sizeBytes;

	@Column(name = "sha256", nullable = false, updatable = false, length = 64)
	private String sha256;

	@Column(name = "logical_content_sha256", nullable = false, updatable = false, length = 64)
	private String logicalContentSha256;

	@Column(name = "storage_bucket", nullable = false, updatable = false, length = 255)
	private String storageBucket;

	@Column(name = "object_key", nullable = false, updatable = false, length = 1024)
	private String objectKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "artifact_manifest", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> artifactManifest;

	@Column(name = "artifact_manifest_sha256", nullable = false, updatable = false, length = 64)
	private String artifactManifestSha256;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CaptureSyncArtifact() {
	}

	private CaptureSyncArtifact(UUID revisionId, long sizeBytes, String sha256, String logicalContentSha256,
			CaptureSyncStoragePort.ObjectReference storage, Map<String, Object> artifactManifest,
			String artifactManifestSha256, Instant createdAt) {
		this.captureSyncArtifactId = UUID.randomUUID();
		this.captureSyncRevisionId = revisionId;
		this.artifactRole = ARTIFACT_ROLE;
		this.schemaVersion = SCHEMA_VERSION;
		this.fileName = FILE_NAME;
		this.mediaType = MEDIA_TYPE;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.logicalContentSha256 = logicalContentSha256;
		this.storageBucket = storage.bucket();
		this.objectKey = storage.objectKey();
		this.artifactManifest = Map.copyOf(artifactManifest);
		this.artifactManifestSha256 = artifactManifestSha256;
		this.createdAt = createdAt;
	}

	static CaptureSyncArtifact register(UUID revisionId, long sizeBytes, String sha256, String logicalContentSha256,
			CaptureSyncStoragePort.ObjectReference storage, Map<String, Object> artifactManifest,
			String artifactManifestSha256, Instant createdAt) {
		return new CaptureSyncArtifact(revisionId, sizeBytes, sha256, logicalContentSha256, storage, artifactManifest,
				artifactManifestSha256, createdAt);
	}

	UUID getCaptureSyncArtifactId() {
		return this.captureSyncArtifactId;
	}

	UUID getCaptureSyncRevisionId() {
		return this.captureSyncRevisionId;
	}

	long getSizeBytes() {
		return this.sizeBytes;
	}

	String getSha256() {
		return this.sha256;
	}

	String getLogicalContentSha256() {
		return this.logicalContentSha256;
	}

	Map<String, Object> getArtifactManifest() {
		return this.artifactManifest;
	}

	String getArtifactManifestSha256() {
		return this.artifactManifestSha256;
	}

	CaptureSyncStoragePort.ObjectReference getObjectReference() {
		return new CaptureSyncStoragePort.ObjectReference(this.storageBucket, this.objectKey);
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

}
