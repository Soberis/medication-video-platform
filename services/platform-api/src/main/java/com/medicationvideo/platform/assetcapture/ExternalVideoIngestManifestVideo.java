package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "external_video_ingest_manifest_video")
class ExternalVideoIngestManifestVideo {

	@Id
	@Column(name = "manifest_video_id", nullable = false, updatable = false)
	private UUID manifestVideoId;

	@Column(name = "manifest_id", nullable = false, updatable = false)
	private UUID manifestId;

	@Column(name = "client_file_id", nullable = false, updatable = false)
	private UUID clientFileId;

	@Column(name = "camera_number", nullable = false, updatable = false)
	private int cameraNumber;

	@Column(name = "file_name", nullable = false, length = 255, updatable = false)
	private String fileName;

	@Column(name = "declared_size_bytes", nullable = false, updatable = false)
	private long declaredSizeBytes;

	@Column(name = "sha256", nullable = false, length = 64, updatable = false)
	private String sha256;

	@Column(name = "record_start_at", updatable = false)
	private Instant recordStartAt;

	protected ExternalVideoIngestManifestVideo() {
	}

	private ExternalVideoIngestManifestVideo(UUID manifestId, ExternalVideoManifestVideo video) {
		this.manifestVideoId = UUID.randomUUID();
		this.manifestId = manifestId;
		this.clientFileId = video.clientFileId();
		this.cameraNumber = video.cameraNumber();
		this.fileName = video.fileName();
		this.declaredSizeBytes = video.declaredSizeBytes();
		this.sha256 = video.sha256();
		this.recordStartAt = video.recordStartAt() == null ? null
				: video.recordStartAt().truncatedTo(ChronoUnit.MICROS);
	}

	static ExternalVideoIngestManifestVideo accept(UUID manifestId, ExternalVideoManifestVideo video) {
		return new ExternalVideoIngestManifestVideo(manifestId, video);
	}

	UUID getManifestId() {
		return this.manifestId;
	}

	UUID getClientFileId() {
		return this.clientFileId;
	}

	int getCameraNumber() {
		return this.cameraNumber;
	}

	String getFileName() {
		return this.fileName;
	}

	long getDeclaredSizeBytes() {
		return this.declaredSizeBytes;
	}

	String getSha256() {
		return this.sha256;
	}

	Instant getRecordStartAt() {
		return this.recordStartAt;
	}

}
