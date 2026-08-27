package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
import java.util.Set;
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

	@Column(name = "capture_group_key", length = 128, updatable = false)
	private String captureGroupKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "capture_group_key_source", length = 64, updatable = false)
	private ExternalVideoCaptureGroupKeySource captureGroupKeySource;

	@Column(name = "capture_group_manifest_id", updatable = false)
	private UUID captureGroupManifestId;

	@Enumerated(EnumType.STRING)
	@Column(name = "receipt_state", nullable = false, length = 32)
	private CaptureGroupReceiptState receiptState;

	@Column(name = "receipt_finalized_at")
	private Instant receiptFinalizedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "current_sync_revision_id")
	private UUID currentSyncRevisionId;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CaptureSession() {
	}

	private CaptureSession(UUID projectId, UUID batchId, UploadFile metadataFile, CaptureSessionMetadataV1 metadata,
			int receivedCameraCount, ExternalVideoIngestManifest groupManifest, Set<Integer> receivedCameraNumbers,
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
		this.assemblyStatus = receivedCameraCount == this.expectedCameraCount ? CaptureSessionAssemblyStatus.COMPLETE
				: CaptureSessionAssemblyStatus.ASSEMBLING;
		this.qualityStatus = CaptureQualityStatus.PENDING;
		if (groupManifest != null) {
			this.captureGroupKey = groupManifest.getCaptureGroupKey();
			this.captureGroupKeySource = groupManifest.getKeySource();
			this.captureGroupManifestId = groupManifest.getManifestId();
			this.receiptState = receiptState(groupManifest, receivedCameraNumbers);
			this.receiptFinalizedAt = groupManifest.getUploadSetFinalizedAt();
		}
		else {
			this.receiptState = CaptureGroupReceiptState.RECEIVING;
		}
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static CaptureSession assemble(UUID projectId, UUID batchId, UploadFile metadataFile,
			CaptureSessionMetadataV1 metadata, int receivedCameraCount, ExternalVideoIngestManifest groupManifest,
			Set<Integer> receivedCameraNumbers, Instant createdAt) {
		return new CaptureSession(projectId, batchId, metadataFile, metadata, receivedCameraCount, groupManifest,
				receivedCameraNumbers, createdAt);
	}

	static CaptureSession importLocal(UUID projectId, UploadBatch batch, CameraSetConfiguration cameraSet,
			Instant createdAt) {
		var session = new CaptureSession(projectId, batch.getBatchId(), cameraSet, createdAt);
		return session;
	}

	private CaptureSession(UUID projectId, UUID batchId, CameraSetConfiguration cameraSet, Instant createdAt) {
		this.captureSessionId = UUID.randomUUID();
		this.projectId = projectId;
		this.batchId = batchId;
		this.deviceId = "PLATFORM_LOCAL";
		this.cameraSetId = cameraSet.getCameraSetId();
		this.metadataSchemaVersion = "platform-local-upload-v1";
		this.expectedCameraCount = 1;
		this.receivedCameraCount = 0;
		this.serverReceiveTime = createdAt;
		this.assemblyStatus = CaptureSessionAssemblyStatus.ASSEMBLING;
		this.qualityStatus = CaptureQualityStatus.PENDING;
		this.receiptState = CaptureGroupReceiptState.RECEIVING;
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

	String getCaptureGroupKey() {
		return this.captureGroupKey;
	}

	ExternalVideoCaptureGroupKeySource getCaptureGroupKeySource() {
		return this.captureGroupKeySource;
	}

	UUID getCaptureGroupManifestId() {
		return this.captureGroupManifestId;
	}

	CaptureGroupReceiptState getReceiptState() {
		return this.receiptState;
	}

	Instant getReceiptFinalizedAt() {
		return this.receiptFinalizedAt;
	}

	void finalizeReceipt(Set<Integer> expectedCameraNumbers, Set<Integer> receivedCameraNumbers, Instant finalizedAt,
			Instant changedAt) {
		if (this.receiptState != CaptureGroupReceiptState.RECEIVING) {
			return;
		}
		this.receiptState = expectedCameraNumbers.equals(receivedCameraNumbers) ? CaptureGroupReceiptState.COMPLETE
				: CaptureGroupReceiptState.INCOMPLETE;
		this.receiptFinalizedAt = finalizedAt;
		this.updatedAt = changedAt;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	UUID getCurrentSyncRevisionId() {
		return this.currentSyncRevisionId;
	}

	long getVersion() {
		return this.version;
	}

	void acceptSyncRevision(UUID revisionId, Instant changedAt) {
		if (revisionId == null) {
			throw new IllegalArgumentException("A sync revision id is required.");
		}
		this.currentSyncRevisionId = revisionId;
		this.updatedAt = changedAt;
	}

	void refreshMediaProjection(List<CameraStream> streams, Instant changedAt) {
		var received = Math.toIntExact(streams.stream()
			.filter(stream -> stream.getStreamStatus() == CameraStreamStatus.INGESTED)
			.map(CameraStream::getCameraChannelId)
			.distinct()
			.count());
		var projectedAssembly = this.assemblyStatus;
		if (this.assemblyStatus != CaptureSessionAssemblyStatus.CLOSED) {
			if (this.assemblyStatus == CaptureSessionAssemblyStatus.INCOMPLETE || streams.stream()
				.anyMatch(stream -> stream.getStreamStatus() == CameraStreamStatus.UNAVAILABLE)) {
				projectedAssembly = CaptureSessionAssemblyStatus.INCOMPLETE;
			}
			else {
				projectedAssembly = received == this.expectedCameraCount && streams.size() == this.expectedCameraCount
						? CaptureSessionAssemblyStatus.COMPLETE : CaptureSessionAssemblyStatus.ASSEMBLING;
			}
		}

		var projectedQuality = technicalQuality(streams, received);
		if (this.receivedCameraCount == received && this.assemblyStatus == projectedAssembly
				&& this.qualityStatus == projectedQuality) {
			return;
		}
		this.receivedCameraCount = received;
		this.assemblyStatus = projectedAssembly;
		this.qualityStatus = projectedQuality;
		this.updatedAt = changedAt;
	}

	private CaptureQualityStatus technicalQuality(List<CameraStream> streams, int received) {
		if (streams.stream().anyMatch(stream -> stream.getQualityStatus() == CaptureQualityStatus.REJECT)) {
			return CaptureQualityStatus.REJECT;
		}
		if (streams.size() != this.expectedCameraCount || received != this.expectedCameraCount
				|| streams.stream().anyMatch(stream -> stream.getQualityStatus() == CaptureQualityStatus.PENDING)) {
			return CaptureQualityStatus.PENDING;
		}
		if (streams.stream().anyMatch(stream -> stream.getQualityStatus() == CaptureQualityStatus.WARNING)) {
			return CaptureQualityStatus.WARNING;
		}
		return CaptureQualityStatus.PASS;
	}

	private static CaptureGroupReceiptState receiptState(ExternalVideoIngestManifest manifest,
			Set<Integer> receivedCameraNumbers) {
		if (!manifest.isUploadSetFinalized()) {
			return CaptureGroupReceiptState.RECEIVING;
		}
		var expected = Set.copyOf(manifest.getExpectedCameraNumbers());
		return expected.equals(receivedCameraNumbers) ? CaptureGroupReceiptState.COMPLETE
				: CaptureGroupReceiptState.INCOMPLETE;
	}

}
