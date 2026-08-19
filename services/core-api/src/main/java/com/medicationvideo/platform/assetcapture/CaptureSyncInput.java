package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable, normalized input facts for one synchronization attempt. The canonical
 * manifest is deliberately independent of map/list arrival order.
 */
public record CaptureSyncInput(UUID projectId, UUID captureSessionId, UUID cameraSetSnapshotId, String captureGroupKey,
		String snapshotDigest, List<ExpectedCamera> expectedCameras, List<SyncStream> streams) {

	public CaptureSyncInput {
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(captureSessionId, "captureSessionId");
		Objects.requireNonNull(cameraSetSnapshotId, "cameraSetSnapshotId");
		if (snapshotDigest == null || !snapshotDigest.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("snapshotDigest must be a lowercase SHA-256 digest.");
		}
		Objects.requireNonNull(expectedCameras, "expectedCameras");
		Objects.requireNonNull(streams, "streams");
		if (expectedCameras.isEmpty()) {
			throw new IllegalArgumentException("At least one expected Camera is required.");
		}
		expectedCameras = expectedCameras.stream()
			.sorted(Comparator.comparingInt(ExpectedCamera::cameraNumber))
			.toList();
		if (expectedCameras.stream().map(ExpectedCamera::cameraNumber).distinct().count() != expectedCameras.size()) {
			throw new IllegalArgumentException("Expected camera numbers must be unique.");
		}
		if (expectedCameras.stream()
			.map(ExpectedCamera::cameraSetSnapshotMemberId)
			.distinct()
			.count() != expectedCameras.size()) {
			throw new IllegalArgumentException("Expected snapshot members must be unique.");
		}
		streams = List.copyOf(streams);
		if (streams.size() != streams.stream().map(SyncStream::cameraStreamId).distinct().count()) {
			throw new IllegalArgumentException("Sync streams must be unique.");
		}
		var expectedNumbers = expectedCameras.stream().map(ExpectedCamera::cameraNumber).toList();
		if (streams.stream().map(SyncStream::cameraNumber).distinct().count() != streams.size()
				|| streams.stream().anyMatch(stream -> !expectedNumbers.contains(stream.cameraNumber()))) {
			throw new IllegalArgumentException("Each SyncStream must retain one unique expected camera number.");
		}
	}

	public String manifestSha256(SyncProfile profile, String algorithmKey, String algorithmVersion) {
		return sha256(canonicalManifest(profile, algorithmKey, algorithmVersion));
	}

	public Map<String, Object> manifest(SyncProfile profile, String algorithmKey, String algorithmVersion) {
		var manifest = new TreeMap<String, Object>();
		manifest.put("algorithm_key", algorithmKey);
		manifest.put("algorithm_version", algorithmVersion);
		manifest.put("camera_set_snapshot_id", this.cameraSetSnapshotId.toString());
		manifest.put("capture_group_key", this.captureGroupKey);
		manifest.put("capture_session_id", this.captureSessionId.toString());
		manifest.put("expected_cameras",
				this.expectedCameras.stream()
					.map(camera -> Map.of("camera_number", camera.cameraNumber(), "camera_set_snapshot_member_id",
							camera.cameraSetSnapshotMemberId().toString()))
					.toList());
		var profileManifest = new LinkedHashMap<String, Object>();
		profileManifest.put("approval", profile.approval().name());
		profileManifest.put("algorithm_key", profile.algorithmKey());
		profileManifest.put("algorithm_version", profile.algorithmVersion());
		profileManifest.put("anchor_drift_rule", profile.anchorDriftRule());
		profileManifest.put("allowed_tolerance_ns", profile.allowedToleranceNs());
		profileManifest.put("nearest_tie_break", profile.nearestTieBreak());
		profileManifest.put("profile_key", profile.profileKey());
		profileManifest.put("profile_sha256", profile.profileSha256());
		profileManifest.put("profile_version", profile.profileVersion());
		profileManifest.put("reference_clock_source", profile.referenceClockSource());
		profileManifest.put("rounding_rule", profile.roundingRule());
		profileManifest.put("timeline_rate_denominator", profile.timelineRate().denominator().toString());
		profileManifest.put("timeline_rate_numerator", profile.timelineRate().numerator().toString());
		profileManifest.put("timeline_step_denominator", profile.timelineStepNs().denominator().toString());
		profileManifest.put("timeline_step_numerator", profile.timelineStepNs().numerator().toString());
		manifest.put("profile", profileManifest);
		manifest.put("project_id", this.projectId.toString());
		manifest.put("snapshot_digest", this.snapshotDigest);
		manifest.put("streams", sortedStreamManifest());
		return manifest;
	}

	private String canonicalManifest(SyncProfile profile, String algorithmKey, String algorithmVersion) {
		var values = new ArrayList<String>();
		values.add("algorithm_key=" + required(algorithmKey, "algorithmKey"));
		values.add("algorithm_version=" + required(algorithmVersion, "algorithmVersion"));
		values.add("camera_set_snapshot_id=" + this.cameraSetSnapshotId);
		values.add("capture_group_key=" + nullable(this.captureGroupKey));
		values.add("capture_session_id=" + this.captureSessionId);
		this.expectedCameras.forEach(camera -> values
			.add("expected_camera=" + camera.cameraNumber() + ":" + camera.cameraSetSnapshotMemberId()));
		values.add("project_id=" + this.projectId);
		values.add("profile_key=" + profile.profileKey());
		values.add("profile_version=" + profile.profileVersion());
		values.add("profile_sha256=" + profile.profileSha256());
		values.add("profile_algorithm_key=" + profile.algorithmKey());
		values.add("profile_algorithm_version=" + profile.algorithmVersion());
		values.add("profile_approval=" + profile.approval().name());
		values.add("profile_rate=" + profile.timelineRate());
		values.add("profile_step_ns=" + profile.timelineStepNs());
		values.add("profile_rounding=" + profile.roundingRule());
		values.add("profile_tie_break=" + profile.nearestTieBreak());
		values.add("profile_tolerance_ns=" + profile.allowedToleranceNs());
		values.add("profile_reference=" + profile.referenceClockSource());
		values.add("profile_anchor_drift=" + profile.anchorDriftRule());
		values.add("snapshot_digest=" + this.snapshotDigest);
		this.streams.stream()
			.sorted(Comparator.comparingInt(SyncStream::cameraNumber)
				.thenComparing(stream -> stream.cameraStreamId().toString()))
			.forEach(stream -> {
				values.add("stream=" + stream.cameraNumber() + ":" + stream.cameraStreamId() + ":"
						+ stream.clipAssetId() + ":" + stream.rawVideoSha256() + ":" + stream.mediaReportId() + ":"
						+ stream.timeBaseDigest());
				var mapping = stream.timeMapping();
				values.add("mapping=" + mapping.anchorSourceNs() + ":" + mapping.anchorCaptureNs() + ":"
						+ rational(mapping.driftPpm()) + ":" + mapping.method() + ":" + mapping.source() + ":"
						+ mapping.anchorCount() + ":" + mapping.trusted());
				stream.sourceFrames()
					.stream()
					.sorted(Comparator.comparingLong(SourceFrame::sourceFrameIndex)
						.thenComparingLong(SourceFrame::sourcePtsNs)
						.thenComparingInt(SourceFrame::sourcePtsOrdinal))
					.forEach(frame -> values
						.add("frame=" + frame.sourceFrameIndex() + ":" + frame.rawPts() + ":" + frame.sourcePtsNs()
								+ ":" + frame.sourcePtsOrdinal() + ":" + nullable(frame.invalidReasonCode())));
			});
		return String.join("\n", values) + "\n";
	}

	private List<Map<String, Object>> sortedStreamManifest() {
		return this.streams.stream()
			.sorted(Comparator.comparingInt(SyncStream::cameraNumber)
				.thenComparing(stream -> stream.cameraStreamId().toString()))
			.<Map<String, Object>>map(stream -> {
				var mapping = stream.timeMapping();
				var result = new TreeMap<String, Object>();
				result.put("camera_number", stream.cameraNumber());
				result.put("camera_stream_id", stream.cameraStreamId().toString());
				result.put("clip_asset_id", stream.clipAssetId().toString());
				result.put("media_report_id", stream.mediaReportId().toString());
				var mappingManifest = new LinkedHashMap<String, Object>();
				mappingManifest.put("anchor_capture_ns", mapping.anchorCaptureNs());
				mappingManifest.put("anchor_count", mapping.anchorCount());
				mappingManifest.put("anchor_source_ns", mapping.anchorSourceNs());
				mappingManifest.put("drift_ppm", rational(mapping.driftPpm()));
				mappingManifest.put("method", mapping.method());
				mappingManifest.put("source", mapping.source());
				mappingManifest.put("trusted", mapping.trusted());
				result.put("mapping", mappingManifest);
				result.put("raw_video_sha256", stream.rawVideoSha256());
				result.put("source_frames",
						stream.sourceFrames()
							.stream()
							.sorted(Comparator.comparingLong(SourceFrame::sourceFrameIndex))
							.map(frame -> {
								var frameManifest = new LinkedHashMap<String, Object>();
								frameManifest.put("invalid_reason_code", frame.invalidReasonCode());
								frameManifest.put("raw_pts", frame.rawPts());
								frameManifest.put("source_frame_index", frame.sourceFrameIndex());
								frameManifest.put("source_pts_ns", frame.sourcePtsNs());
								frameManifest.put("source_pts_ordinal", frame.sourcePtsOrdinal());
								return frameManifest;
							})
							.toList());
				result.put("time_base_digest", stream.timeBaseDigest());
				return result;
			})
			.toList();
	}

	private static String rational(Rational value) {
		return value == null ? "null" : value.toString();
	}

	private static String nullable(String value) {
		return value == null ? "null" : value;
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must be explicit.");
		}
		return value;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in the Java runtime.", exception);
		}
	}

}
