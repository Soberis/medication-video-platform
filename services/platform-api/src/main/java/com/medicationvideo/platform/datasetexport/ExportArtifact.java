package com.medicationvideo.platform.datasetexport;

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
@Table(name = "export_artifact")
class ExportArtifact {

	static final String ARTIFACT_ROLE = "DATASET_PACKAGE";
	static final String FILE_NAME = "dataset-export.zip";
	static final String MEDIA_TYPE = "application/zip";

	@Id
	@Column(name = "export_artifact_id", nullable = false, updatable = false)
	private UUID exportArtifactId;

	@Column(name = "dataset_export_id", nullable = false, updatable = false)
	private UUID datasetExportId;

	@Column(name = "dataset_export_attempt_id", nullable = false, updatable = false)
	private UUID datasetExportAttemptId;

	@Column(name = "artifact_role", nullable = false, updatable = false, length = 32)
	private String artifactRole;

	@Column(name = "file_name", nullable = false, updatable = false, length = 255)
	private String fileName;

	@Column(name = "media_type", nullable = false, updatable = false, length = 128)
	private String mediaType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private long sizeBytes;

	@Column(name = "sha256", nullable = false, updatable = false, length = 64)
	private String sha256;

	@Column(name = "storage_bucket", nullable = false, updatable = false, length = 255)
	private String storageBucket;

	@Column(name = "object_key", nullable = false, updatable = false, length = 1024)
	private String objectKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "artifact_manifest", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> artifactManifest;

	@Column(name = "artifact_manifest_digest", nullable = false, updatable = false, length = 64)
	private String artifactManifestDigest;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ExportArtifact() {
	}

	private ExportArtifact(
			DatasetExport export,
			DatasetExportAttempt attempt,
			long sizeBytes,
			String sha256,
			DatasetExportStoragePort.CreatedUpload upload,
			Map<String, Object> artifactManifest,
			String artifactManifestDigest,
			Instant createdAt) {
		this.exportArtifactId = UUID.randomUUID();
		this.datasetExportId = export.getDatasetExportId();
		this.datasetExportAttemptId = attempt.getDatasetExportAttemptId();
		this.artifactRole = ARTIFACT_ROLE;
		this.fileName = FILE_NAME;
		this.mediaType = MEDIA_TYPE;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.storageBucket = upload.bucket();
		this.objectKey = upload.objectKey();
		this.artifactManifest = Map.copyOf(artifactManifest);
		this.artifactManifestDigest = artifactManifestDigest;
		this.createdAt = createdAt;
	}

	static ExportArtifact register(
			DatasetExport export,
			DatasetExportAttempt attempt,
			long sizeBytes,
			String sha256,
			DatasetExportStoragePort.CreatedUpload upload,
			Map<String, Object> artifactManifest,
			String artifactManifestDigest,
			Instant createdAt) {
		return new ExportArtifact(
				export,
				attempt,
				sizeBytes,
				sha256,
				upload,
				artifactManifest,
				artifactManifestDigest,
				createdAt);
	}

	UUID getExportArtifactId() {
		return this.exportArtifactId;
	}

	UUID getDatasetExportId() {
		return this.datasetExportId;
	}

	long getSizeBytes() {
		return this.sizeBytes;
	}

	String getSha256() {
		return this.sha256;
	}

	String getFileName() {
		return this.fileName;
	}

	String getMediaType() {
		return this.mediaType;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	DatasetExportStoragePort.ObjectReference getObjectReference() {
		return new DatasetExportStoragePort.ObjectReference(this.storageBucket, this.objectKey);
	}
}
