package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "capture_sync_revision")
class CaptureSyncRevision {

	@Id
	@Column(name = "capture_sync_revision_id", nullable = false, updatable = false)
	private UUID captureSyncRevisionId;

	@Column(name = "capture_sync_job_id", nullable = false, updatable = false)
	private UUID captureSyncJobId;

	@Column(name = "capture_sync_job_attempt_id", nullable = false, updatable = false)
	private UUID captureSyncJobAttemptId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "camera_set_snapshot_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotId;

	@Column(name = "supersedes_sync_revision_id", updatable = false)
	private UUID supersedesSyncRevisionId;

	@Column(name = "reference_camera_number", updatable = false)
	private Integer referenceCameraNumber;

	@Column(name = "reference_clock_source", nullable = false, length = 128, updatable = false)
	private String referenceClockSource;

	@Column(name = "time_basis_source", nullable = false, length = 128, updatable = false)
	private String timeBasisSource;

	@Column(name = "capture_origin_utc", updatable = false)
	private Instant captureOriginUtc;

	@Column(name = "time_precision_ns", updatable = false)
	private Long timePrecisionNs;

	@Column(name = "timeline_rate_numerator", nullable = false, updatable = false)
	private long timelineRateNumerator;

	@Column(name = "timeline_rate_denominator", nullable = false, updatable = false)
	private long timelineRateDenominator;

	@Column(name = "timeline_step_numerator_ns", nullable = false, updatable = false)
	private long timelineStepNumeratorNs;

	@Column(name = "timeline_step_denominator", nullable = false, updatable = false)
	private long timelineStepDenominator;

	@Column(name = "allowed_tolerance_ns", nullable = false, updatable = false)
	private long allowedToleranceNs;

	@Column(name = "algorithm_key", nullable = false, length = 128, updatable = false)
	private String algorithmKey;

	@Column(name = "algorithm_version", nullable = false, length = 64, updatable = false)
	private String algorithmVersion;

	@Column(name = "sync_profile_key", nullable = false, length = 128, updatable = false)
	private String syncProfileKey;

	@Column(name = "sync_profile_version", nullable = false, length = 64, updatable = false)
	private String syncProfileVersion;

	@Column(name = "sync_profile_sha256", nullable = false, length = 64, updatable = false)
	private String syncProfileSha256;

	@Column(name = "input_manifest_sha256", nullable = false, length = 64, updatable = false)
	private String inputManifestSha256;

	@Column(name = "logical_content_sha256", nullable = false, length = 64, updatable = false)
	private String logicalContentSha256;

	@Column(name = "camera_count_expected", nullable = false, updatable = false)
	private int cameraCountExpected;

	@Column(name = "camera_count_received", nullable = false, updatable = false)
	private long cameraCountReceived;

	@Column(name = "sync_frame_count", nullable = false, updatable = false)
	private long syncFrameCount;

	@Column(name = "present_frame_count", nullable = false, updatable = false)
	private long presentFrameCount;

	@Column(name = "missing_frame_count", nullable = false, updatable = false)
	private long missingFrameCount;

	@Column(name = "invalid_frame_count", nullable = false, updatable = false)
	private long invalidFrameCount;

	@Column(name = "max_sync_error_ns", updatable = false)
	private Long maxSyncErrorNs;

	@Column(name = "p95_sync_error_ns", updatable = false)
	private Long p95SyncErrorNs;

	@Column(name = "processing_duration_ms", nullable = false, updatable = false)
	private long processingDurationMs;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "created_by", nullable = false, length = 128, updatable = false)
	private String createdBy;

	protected CaptureSyncRevision() {
	}

	private CaptureSyncRevision(UUID revisionId, CaptureSyncJob job, CaptureSyncJobAttempt attempt,
			UUID supersedesRevisionId, SyncProfile profile, CaptureSyncRevisionSummary summary, String createdBy,
			Instant createdAt) {
		if (!job.getCaptureSyncJobId().equals(attempt.getCaptureSyncJobId())) {
			throw new IllegalArgumentException("A revision attempt must belong to its synchronization job.");
		}
		if (attempt.getStatus() != CaptureSyncJobAttemptStatus.SUCCEEDED) {
			throw new IllegalArgumentException("Only a succeeded synchronization attempt can create a revision.");
		}
		if (!job.getAlgorithmKey().equals(profile.algorithmKey())
				|| !job.getAlgorithmVersion().equals(profile.algorithmVersion())
				|| !job.getSyncProfileKey().equals(profile.profileKey())
				|| !job.getSyncProfileVersion().equals(profile.profileVersion())
				|| !job.getSyncProfileSha256().equals(profile.profileSha256())) {
			throw new IllegalArgumentException("A revision profile must match the job's frozen synchronization input.");
		}
		this.captureSyncRevisionId = java.util.Objects.requireNonNull(revisionId, "revisionId");
		this.captureSyncJobId = job.getCaptureSyncJobId();
		this.captureSyncJobAttemptId = attempt.getCaptureSyncJobAttemptId();
		this.projectId = job.getProjectId();
		this.captureSessionId = job.getCaptureSessionId();
		this.cameraSetSnapshotId = job.getCameraSetSnapshotId();
		this.supersedesSyncRevisionId = supersedesRevisionId;
		this.referenceCameraNumber = summary.referenceCameraNumber();
		this.referenceClockSource = summary.referenceClockSource();
		this.timeBasisSource = summary.timeBasisSource();
		this.captureOriginUtc = summary.captureOriginUtc();
		this.timePrecisionNs = summary.timePrecisionNs();
		this.timelineRateNumerator = profile.timelineRate().numeratorLongExact();
		this.timelineRateDenominator = profile.timelineRate().denominatorLongExact();
		this.timelineStepNumeratorNs = profile.timelineStepNs().numeratorLongExact();
		this.timelineStepDenominator = profile.timelineStepNs().denominatorLongExact();
		this.allowedToleranceNs = profile.allowedToleranceNs();
		this.algorithmKey = profile.algorithmKey();
		this.algorithmVersion = profile.algorithmVersion();
		this.syncProfileKey = profile.profileKey();
		this.syncProfileVersion = profile.profileVersion();
		this.syncProfileSha256 = profile.profileSha256();
		this.inputManifestSha256 = job.getInputManifestSha256();
		this.logicalContentSha256 = summary.logicalContentSha256();
		this.cameraCountExpected = job.getExpectedCameraCount();
		this.cameraCountReceived = summary.cameraCountReceived();
		this.syncFrameCount = summary.syncFrameCount();
		this.presentFrameCount = summary.presentFrameCount();
		this.missingFrameCount = summary.missingFrameCount();
		this.invalidFrameCount = summary.invalidFrameCount();
		this.maxSyncErrorNs = summary.maxSyncErrorNs();
		this.p95SyncErrorNs = summary.p95SyncErrorNs();
		this.processingDurationMs = summary.processingDurationMs();
		this.createdBy = required(createdBy, "createdBy");
		this.createdAt = createdAt;
	}

	static CaptureSyncRevision accept(UUID revisionId, CaptureSyncJob job, CaptureSyncJobAttempt attempt,
			UUID supersedesRevisionId, SyncProfile profile, CaptureSyncRevisionSummary summary, String createdBy,
			Instant createdAt) {
		return new CaptureSyncRevision(revisionId, job, attempt, supersedesRevisionId, profile, summary, createdBy,
				createdAt);
	}

	UUID getCaptureSyncRevisionId() {
		return this.captureSyncRevisionId;
	}

	UUID getCaptureSyncJobId() {
		return this.captureSyncJobId;
	}

	UUID getCaptureSyncJobAttemptId() {
		return this.captureSyncJobAttemptId;
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

	UUID getSupersedesSyncRevisionId() {
		return this.supersedesSyncRevisionId;
	}

	Integer getReferenceCameraNumber() {
		return this.referenceCameraNumber;
	}

	String getReferenceClockSource() {
		return this.referenceClockSource;
	}

	String getTimeBasisSource() {
		return this.timeBasisSource;
	}

	long getTimelineRateNumerator() {
		return this.timelineRateNumerator;
	}

	long getTimelineRateDenominator() {
		return this.timelineRateDenominator;
	}

	long getTimelineStepNumeratorNs() {
		return this.timelineStepNumeratorNs;
	}

	long getTimelineStepDenominator() {
		return this.timelineStepDenominator;
	}

	long getAllowedToleranceNs() {
		return this.allowedToleranceNs;
	}

	String getAlgorithmKey() {
		return this.algorithmKey;
	}

	String getAlgorithmVersion() {
		return this.algorithmVersion;
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

	String getInputManifestSha256() {
		return this.inputManifestSha256;
	}

	String getLogicalContentSha256() {
		return this.logicalContentSha256;
	}

	int getCameraCountExpected() {
		return this.cameraCountExpected;
	}

	long getCameraCountReceived() {
		return this.cameraCountReceived;
	}

	long getSyncFrameCount() {
		return this.syncFrameCount;
	}

	long getPresentFrameCount() {
		return this.presentFrameCount;
	}

	long getMissingFrameCount() {
		return this.missingFrameCount;
	}

	long getInvalidFrameCount() {
		return this.invalidFrameCount;
	}

	Long getMaxSyncErrorNs() {
		return this.maxSyncErrorNs;
	}

	Long getP95SyncErrorNs() {
		return this.p95SyncErrorNs;
	}

	long getProcessingDurationMs() {
		return this.processingDurationMs;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getCaptureOriginUtc() {
		return this.captureOriginUtc;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must be explicit.");
		}
		return value;
	}

}
