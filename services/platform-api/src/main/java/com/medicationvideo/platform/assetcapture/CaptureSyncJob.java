package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "capture_sync_job")
class CaptureSyncJob {

	@Id
	@Column(name = "capture_sync_job_id", nullable = false, updatable = false)
	private UUID captureSyncJobId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "camera_set_snapshot_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotId;

	@Column(name = "expected_camera_count", nullable = false, updatable = false)
	private int expectedCameraCount;

	@Column(name = "input_manifest_sha256", nullable = false, length = 64, updatable = false)
	private String inputManifestSha256;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_manifest", nullable = false, columnDefinition = "jsonb", updatable = false)
	private Map<String, Object> inputManifest;

	@Column(name = "sync_profile_key", nullable = false, length = 128, updatable = false)
	private String syncProfileKey;

	@Column(name = "sync_profile_version", nullable = false, length = 64, updatable = false)
	private String syncProfileVersion;

	@Column(name = "sync_profile_sha256", nullable = false, length = 64, updatable = false)
	private String syncProfileSha256;

	@Column(name = "algorithm_key", nullable = false, length = 128, updatable = false)
	private String algorithmKey;

	@Column(name = "algorithm_version", nullable = false, length = 64, updatable = false)
	private String algorithmVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private CaptureSyncJobStatus status;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "requested_by", nullable = false, length = 128, updatable = false)
	private String requestedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CaptureSyncJob() {
	}

	private CaptureSyncJob(UUID projectId, CaptureSyncInput input, SyncProfile profile, String algorithmKey,
			String algorithmVersion, String idempotencyKey, String requestFingerprint, String requestedBy,
			Instant createdAt) {
		this.captureSyncJobId = UUID.randomUUID();
		this.projectId = projectId;
		this.captureSessionId = input.captureSessionId();
		this.cameraSetSnapshotId = input.cameraSetSnapshotId();
		this.expectedCameraCount = input.expectedCameras().size();
		this.inputManifestSha256 = input.manifestSha256(profile, algorithmKey, algorithmVersion);
		this.inputManifest = input.manifest(profile, algorithmKey, algorithmVersion);
		this.syncProfileKey = profile.profileKey();
		this.syncProfileVersion = profile.profileVersion();
		this.syncProfileSha256 = profile.profileSha256();
		this.algorithmKey = algorithmKey;
		this.algorithmVersion = algorithmVersion;
		this.status = CaptureSyncJobStatus.QUEUED;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.requestedBy = requestedBy;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static CaptureSyncJob queue(CaptureSyncInput input, SyncProfile profile, String algorithmKey,
			String algorithmVersion, String idempotencyKey, String requestFingerprint, String requestedBy,
			Instant createdAt) {
		return new CaptureSyncJob(input.projectId(), input, profile, required(algorithmKey, "algorithmKey"),
				required(algorithmVersion, "algorithmVersion"), required(idempotencyKey, "idempotencyKey"),
				requiredSha256(requestFingerprint, "requestFingerprint"), required(requestedBy, "requestedBy"),
				createdAt);
	}

	void start(Instant changedAt) {
		if (this.status == CaptureSyncJobStatus.RUNNING) {
			return;
		}
		if (this.status != CaptureSyncJobStatus.QUEUED && this.status != CaptureSyncJobStatus.RETRY_WAIT) {
			throw new IllegalStateException("The capture synchronization job is not queued for execution.");
		}
		this.status = CaptureSyncJobStatus.RUNNING;
		this.updatedAt = changedAt;
	}

	void succeed(Instant changedAt) {
		transitionFromRunning(CaptureSyncJobStatus.SUCCEEDED, changedAt);
	}

	void fail(Instant changedAt) {
		transitionFromRunning(CaptureSyncJobStatus.FAILED, changedAt);
	}

	void waitForRetry(Instant changedAt) {
		transitionFromRunning(CaptureSyncJobStatus.RETRY_WAIT, changedAt);
	}

	void requeueForRetry(Instant changedAt) {
		if (this.status != CaptureSyncJobStatus.FAILED && this.status != CaptureSyncJobStatus.RETRY_WAIT) {
			throw new IllegalStateException("Only a failed synchronization job can be retried.");
		}
		this.status = CaptureSyncJobStatus.QUEUED;
		this.updatedAt = changedAt;
	}

	private void transitionFromRunning(CaptureSyncJobStatus target, Instant changedAt) {
		if (this.status != CaptureSyncJobStatus.RUNNING) {
			throw new IllegalStateException("The capture synchronization job is not running.");
		}
		this.status = target;
		this.updatedAt = changedAt;
	}

	UUID getCaptureSyncJobId() {
		return this.captureSyncJobId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getCaptureSessionId() {
		return this.captureSessionId;
	}

	UUID getCameraSetSnapshotId() {
		return this.cameraSetSnapshotId;
	}

	int getExpectedCameraCount() {
		return this.expectedCameraCount;
	}

	String getInputManifestSha256() {
		return this.inputManifestSha256;
	}

	Map<String, Object> getInputManifest() {
		return this.inputManifest;
	}

	List<ExpectedCamera> getExpectedCameras() {
		var rawExpectedCameras = this.inputManifest.get("expected_cameras");
		if (!(rawExpectedCameras instanceof List<?> values) || values.size() != this.expectedCameraCount) {
			throw new IllegalStateException("The frozen input manifest has an invalid expected-camera set.");
		}
		return values.stream().map(CaptureSyncJob::expectedCamera).toList();
	}

	Map<Integer, FrozenSyncStream> getFrozenStreams() {
		var rawStreams = this.inputManifest.get("streams");
		if (!(rawStreams instanceof List<?> values)) {
			throw new IllegalStateException("The frozen input manifest has an invalid stream set.");
		}
		var streams = new HashMap<Integer, FrozenSyncStream>();
		for (var value : values) {
			var stream = frozenStream(value);
			if (streams.put(stream.cameraNumber(), stream) != null) {
				throw new IllegalStateException("The frozen input manifest contains duplicate stream cameras.");
			}
		}
		return Map.copyOf(streams);
	}

	String getSyncProfileKey() {
		return this.syncProfileKey;
	}

	String getSyncProfileVersion() {
		return this.syncProfileVersion;
	}

	String getSyncProfileSha256() {
		return this.syncProfileSha256;
	}

	String getFrozenReferenceClockSource() {
		var rawProfile = this.inputManifest.get("profile");
		if (!(rawProfile instanceof Map<?, ?> profile)
				|| !(profile.get("reference_clock_source") instanceof String referenceClockSource)
				|| referenceClockSource.isBlank()) {
			throw new IllegalStateException("The frozen input manifest has no valid reference clock source.");
		}
		return referenceClockSource;
	}

	String getAlgorithmKey() {
		return this.algorithmKey;
	}

	String getAlgorithmVersion() {
		return this.algorithmVersion;
	}

	CaptureSyncJobStatus getStatus() {
		return this.status;
	}

	String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	String getRequestedBy() {
		return this.requestedBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must be explicit.");
		}
		return value;
	}

	private static String requiredSha256(String value, String name) {
		var normalized = required(value, name);
		if (!normalized.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest.");
		}
		return normalized;
	}

	private static ExpectedCamera expectedCamera(Object value) {
		if (!(value instanceof Map<?, ?> camera) || !(camera.get("camera_number") instanceof Number cameraNumber)
				|| !(camera.get("camera_set_snapshot_member_id") instanceof String memberId)
				|| cameraNumber.longValue() != cameraNumber.intValue()) {
			throw new IllegalStateException("The frozen input manifest contains an invalid expected camera.");
		}
		try {
			return new ExpectedCamera(UUID.fromString(memberId), cameraNumber.intValue());
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("The frozen input manifest contains an invalid expected camera.",
					exception);
		}
	}

	private static FrozenSyncStream frozenStream(Object value) {
		if (!(value instanceof Map<?, ?> stream) || !(stream.get("mapping") instanceof Map<?, ?> mapping)) {
			throw new IllegalStateException("The frozen input manifest contains an invalid stream.");
		}
		try {
			return new FrozenSyncStream(integer(stream, "camera_number"), uuid(stream, "camera_stream_id"),
					uuid(stream, "clip_asset_id"), string(stream, "raw_video_sha256"), uuid(stream, "media_report_id"),
					string(stream, "time_base_digest"), longValue(mapping, "anchor_source_ns"),
					longValue(mapping, "anchor_capture_ns"), string(mapping, "drift_ppm"), string(mapping, "method"),
					string(mapping, "source"), integer(mapping, "anchor_count"), bool(mapping, "trusted"));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("The frozen input manifest contains an invalid stream.", exception);
		}
	}

	private static int integer(Map<?, ?> value, String key) {
		var raw = value.get(key);
		if (!(raw instanceof Number number) || number.longValue() != number.intValue()) {
			throw new IllegalArgumentException(key + " must be an integer.");
		}
		return number.intValue();
	}

	private static long longValue(Map<?, ?> value, String key) {
		var raw = value.get(key);
		if (!(raw instanceof Number number)) {
			throw new IllegalArgumentException(key + " must be an integer.");
		}
		return number.longValue();
	}

	private static String string(Map<?, ?> value, String key) {
		var raw = value.get(key);
		if (!(raw instanceof String text) || text.isBlank()) {
			throw new IllegalArgumentException(key + " must be explicit.");
		}
		return text;
	}

	private static UUID uuid(Map<?, ?> value, String key) {
		return UUID.fromString(string(value, key));
	}

	private static boolean bool(Map<?, ?> value, String key) {
		var raw = value.get(key);
		if (!(raw instanceof Boolean result)) {
			throw new IllegalArgumentException(key + " must be boolean.");
		}
		return result;
	}

	record FrozenSyncStream(int cameraNumber, UUID cameraStreamId, UUID clipAssetId, String rawVideoSha256,
			UUID mediaReportId, String timeBaseDigest, long anchorSourceNs, long anchorCaptureNs, String driftPpm,
			String method, String source, int anchorCount, boolean trusted) {

		boolean matches(SyncStream stream) {
			if (stream == null || this.cameraNumber != stream.cameraNumber()
					|| !this.cameraStreamId.equals(stream.cameraStreamId())
					|| !this.clipAssetId.equals(stream.clipAssetId())
					|| !this.rawVideoSha256.equals(stream.rawVideoSha256())
					|| !this.mediaReportId.equals(stream.mediaReportId())
					|| !this.timeBaseDigest.equals(stream.timeBaseDigest())) {
				return false;
			}
			var mapping = stream.timeMapping();
			var actualDrift = mapping.driftPpm() == null ? "null" : mapping.driftPpm().toString();
			return this.anchorSourceNs == mapping.anchorSourceNs() && this.anchorCaptureNs == mapping.anchorCaptureNs()
					&& this.driftPpm.equals(actualDrift) && this.method.equals(mapping.method())
					&& this.source.equals(mapping.source()) && this.anchorCount == mapping.anchorCount()
					&& this.trusted == mapping.trusted();
		}
	}

}
