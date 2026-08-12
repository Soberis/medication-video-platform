package com.medicationvideo.platform.assetcapture;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "capture_sync_stream_revision")
class CaptureSyncStreamRevision {

	@Id
	@Column(name = "capture_sync_stream_revision_id", nullable = false, updatable = false)
	private UUID captureSyncStreamRevisionId;

	@Column(name = "capture_sync_revision_id", nullable = false, updatable = false)
	private UUID captureSyncRevisionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "camera_set_snapshot_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotId;

	@Column(name = "camera_set_snapshot_member_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotMemberId;

	@Column(name = "camera_number", nullable = false, updatable = false)
	private int cameraNumber;

	@Column(name = "camera_stream_id", updatable = false)
	private UUID cameraStreamId;

	@Column(name = "clip_asset_id", updatable = false)
	private UUID clipAssetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "stream_presence_state", nullable = false, length = 16, updatable = false)
	private CaptureSyncStreamPresenceState streamPresenceState;

	@Column(name = "source_input_sha256", length = 64, updatable = false)
	private String sourceInputSha256;

	@Column(name = "source_media_report_id", updatable = false)
	private UUID sourceMediaReportId;

	@Column(name = "source_time_base_digest", length = 64, updatable = false)
	private String sourceTimeBaseDigest;

	@Column(name = "anchor_source_pts_ns", updatable = false)
	private Long anchorSourcePtsNs;

	@Column(name = "anchor_capture_timestamp_ns", updatable = false)
	private Long anchorCaptureTimestampNs;

	@Column(name = "drift_ppm", precision = 38, scale = 18, updatable = false)
	private BigDecimal driftPpm;

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

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CaptureSyncStreamRevision() {
	}

	private CaptureSyncStreamRevision(CaptureSyncRevision revision, ExpectedCamera expectedCamera, SyncStream stream,
			CaptureSyncStreamPresenceState presenceState, long presentFrameCount, long missingFrameCount,
			long invalidFrameCount, Long maxSyncErrorNs, Long p95SyncErrorNs, Instant createdAt) {
		if (presentFrameCount < 0 || missingFrameCount < 0 || invalidFrameCount < 0
				|| maxSyncErrorNs != null && maxSyncErrorNs < 0 || p95SyncErrorNs != null && p95SyncErrorNs < 0) {
			throw new IllegalArgumentException("Stream revision counts and errors must not be negative.");
		}
		if (presenceState == CaptureSyncStreamPresenceState.MISSING && stream != null) {
			throw new IllegalArgumentException("A missing stream must not bind a source stream.");
		}
		if (presenceState != CaptureSyncStreamPresenceState.MISSING && stream == null) {
			throw new IllegalArgumentException("A present or invalid stream must bind source facts.");
		}
		if (stream != null && !stream.timeMapping().trusted()) {
			throw new IllegalArgumentException("A stream revision must bind a trusted stream-to-Capture mapping.");
		}
		if (stream != null && stream.cameraNumber() != expectedCamera.cameraNumber()) {
			throw new IllegalArgumentException("A stream revision must retain its expected camera identity.");
		}
		this.captureSyncStreamRevisionId = UUID.randomUUID();
		this.captureSyncRevisionId = revision.getCaptureSyncRevisionId();
		this.projectId = revision.getProjectId();
		this.captureSessionId = revision.getCaptureSessionId();
		this.cameraSetSnapshotId = revision.getCameraSetSnapshotId();
		this.cameraSetSnapshotMemberId = expectedCamera.cameraSetSnapshotMemberId();
		this.cameraNumber = expectedCamera.cameraNumber();
		this.cameraStreamId = stream == null ? null : stream.cameraStreamId();
		this.clipAssetId = stream == null ? null : stream.clipAssetId();
		this.streamPresenceState = presenceState;
		this.sourceInputSha256 = stream == null ? null : stream.rawVideoSha256();
		this.sourceMediaReportId = stream == null ? null : stream.mediaReportId();
		this.sourceTimeBaseDigest = stream == null ? null : stream.timeBaseDigest();
		this.anchorSourcePtsNs = stream == null ? null : stream.timeMapping().anchorSourceNs();
		this.anchorCaptureTimestampNs = stream == null ? null : stream.timeMapping().anchorCaptureNs();
		this.driftPpm = stream == null || stream.timeMapping().driftPpm() == null ? null
				: decimal(stream.timeMapping().driftPpm());
		this.presentFrameCount = presentFrameCount;
		this.missingFrameCount = missingFrameCount;
		this.invalidFrameCount = invalidFrameCount;
		this.maxSyncErrorNs = maxSyncErrorNs;
		this.p95SyncErrorNs = p95SyncErrorNs;
		this.createdAt = createdAt;
	}

	static CaptureSyncStreamRevision accept(CaptureSyncRevision revision, ExpectedCamera expectedCamera,
			SyncStream stream, CaptureSyncStreamPresenceState presenceState, long presentFrameCount,
			long missingFrameCount, long invalidFrameCount, Long maxSyncErrorNs, Long p95SyncErrorNs,
			Instant createdAt) {
		return new CaptureSyncStreamRevision(revision, expectedCamera, stream, presenceState, presentFrameCount,
				missingFrameCount, invalidFrameCount, maxSyncErrorNs, p95SyncErrorNs, createdAt);
	}

	UUID getCaptureSyncStreamRevisionId() {
		return this.captureSyncStreamRevisionId;
	}

	UUID getCaptureSyncRevisionId() {
		return this.captureSyncRevisionId;
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

	UUID getCameraSetSnapshotMemberId() {
		return this.cameraSetSnapshotMemberId;
	}

	int getCameraNumber() {
		return this.cameraNumber;
	}

	UUID getCameraStreamId() {
		return this.cameraStreamId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	CaptureSyncStreamPresenceState getStreamPresenceState() {
		return this.streamPresenceState;
	}

	String getSourceInputSha256() {
		return this.sourceInputSha256;
	}

	UUID getSourceMediaReportId() {
		return this.sourceMediaReportId;
	}

	String getSourceTimeBaseDigest() {
		return this.sourceTimeBaseDigest;
	}

	Long getAnchorSourcePtsNs() {
		return this.anchorSourcePtsNs;
	}

	Long getAnchorCaptureTimestampNs() {
		return this.anchorCaptureTimestampNs;
	}

	BigDecimal getDriftPpm() {
		return this.driftPpm;
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

	Instant getCreatedAt() {
		return this.createdAt;
	}

	private static BigDecimal decimal(Rational rational) {
		return new BigDecimal(rational.numerator()).divide(new BigDecimal(rational.denominator()), 18,
				RoundingMode.HALF_EVEN);
	}

}
