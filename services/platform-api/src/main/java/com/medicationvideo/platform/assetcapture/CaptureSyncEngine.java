package com.medicationvideo.platform.assetcapture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic, profile-driven Capture timeline and nearest-PTS mapper. It creates no
 * interpolated frames and never uses source frame indices as cross-camera time.
 */
public final class CaptureSyncEngine {

	public List<Long> buildTimeline(long durationNs, SyncProfile profile) {
		return buildTimeline(Rational.whole(durationNs), profile);
	}

	public List<Long> buildTimeline(Rational durationNs, SyncProfile profile) {
		return buildTimeline(0, durationNs, profile);
	}

	public List<Long> buildTimeline(long originNs, Rational durationNs, SyncProfile profile) {
		if (originNs < 0) {
			throw new IllegalArgumentException("Capture timeline origin must not be negative.");
		}
		Objects.requireNonNull(durationNs, "durationNs");
		Objects.requireNonNull(profile, "profile");
		if (durationNs.signum() <= 0) {
			throw new IllegalArgumentException("Capture duration must be positive.");
		}
		var step = profile.timelineStepNs();
		var frameCount = durationNs.divide(step).ceilLong();
		var timestamps = new ArrayList<Long>(Math.toIntExact(frameCount));
		var endNs = Math.addExact(originNs, durationNs.roundHalfEvenLong());
		for (long index = 0; index < frameCount; index++) {
			var timestamp = Math.addExact(originNs, Rational.whole(index).multiply(step).roundHalfEvenLong());
			if (timestamp >= endNs) {
				break;
			}
			timestamps.add(timestamp);
		}
		return List.copyOf(timestamps);
	}

	public List<FrameSlot> buildFrameSlots(UUID captureSessionId, UUID syncRevisionId, Rational durationNs,
			List<Integer> expectedCameraNumbers, Map<Integer, SyncStream> streams, SyncProfile profile) {
		return buildFrameSlots(captureSessionId, syncRevisionId, 0, durationNs, expectedCameraNumbers, streams,
				profile);
	}

	public List<FrameSlot> buildFrameSlots(UUID captureSessionId, UUID syncRevisionId, long originNs,
			Rational durationNs, List<Integer> expectedCameraNumbers, Map<Integer, SyncStream> streams,
			SyncProfile profile) {
		Objects.requireNonNull(captureSessionId, "captureSessionId");
		Objects.requireNonNull(syncRevisionId, "syncRevisionId");
		Objects.requireNonNull(expectedCameraNumbers, "expectedCameraNumbers");
		Objects.requireNonNull(streams, "streams");
		Objects.requireNonNull(profile, "profile");
		var expected = expectedCameraNumbers.stream().sorted().toList();
		if (expected.isEmpty() || expected.stream().distinct().count() != expected.size()
				|| expected.stream().anyMatch(number -> number < 1 || number > 64)) {
			throw new IllegalArgumentException("Expected camera numbers must be unique values from 1 through 64.");
		}
		var unexpected = streams.keySet().stream().filter(number -> !expected.contains(number)).sorted().toList();
		if (!unexpected.isEmpty()) {
			throw new IllegalArgumentException("Unexpected camera numbers: " + unexpected);
		}
		for (var entry : streams.entrySet()) {
			if (entry.getValue() == null || !entry.getValue().timeMapping().trusted()) {
				throw new SynchronizationUnavailableException(entry.getKey());
			}
		}
		var timeline = buildTimeline(originNs, durationNs, profile);
		var result = new ArrayList<FrameSlot>(Math.multiplyExact(timeline.size(), expected.size()));
		for (var syncFrameIndex = 0; syncFrameIndex < timeline.size(); syncFrameIndex++) {
			var captureTimestampNs = timeline.get(syncFrameIndex);
			for (var cameraNumber : expected) {
				var stream = streams.get(cameraNumber);
				if (stream == null) {
					result.add(FrameSlot.from(captureSessionId, syncRevisionId, syncFrameIndex, captureTimestampNs,
							cameraNumber, null, null, FrameMappingResult.missing("CAMERA_STREAM_MISSING")));
				}
				else {
					var mapping = mapStream(captureTimestampNs, stream, profile.allowedToleranceNs(), cameraNumber);
					result.add(FrameSlot.from(captureSessionId, syncRevisionId, syncFrameIndex, captureTimestampNs,
							cameraNumber, stream.cameraStreamId(), stream.clipAssetId(), mapping));
				}
			}
		}
		return List.copyOf(result);
	}

	public FrameMappingResult mapStream(long captureTimestampNs, SyncStream stream, long allowedToleranceNs) {
		return mapStream(captureTimestampNs, stream, allowedToleranceNs, 0);
	}

	private FrameMappingResult mapStream(long captureTimestampNs, SyncStream stream, long allowedToleranceNs,
			int cameraNumber) {
		Objects.requireNonNull(stream, "stream");
		if (captureTimestampNs < 0 || allowedToleranceNs < 0) {
			throw new IllegalArgumentException("Capture timestamp and tolerance must not be negative.");
		}
		var mapping = stream.timeMapping();
		if (!mapping.trusted()) {
			throw new SynchronizationUnavailableException(cameraNumber);
		}
		if (stream.sourceFrames().isEmpty()) {
			return FrameMappingResult.missing("NO_SOURCE_FRAME");
		}
		var targetSourceNs = mapping.mapCaptureToSourceTime(captureTimestampNs);
		var frame = nearestFrame(stream.sourceFrames(), targetSourceNs);
		var mappedCaptureNs = mapping.mapSourceToCaptureTime(frame.sourcePtsNs());
		var syncErrorNs = Math.subtractExact(mappedCaptureNs, captureTimestampNs);
		if (syncErrorNs > allowedToleranceNs || syncErrorNs < -allowedToleranceNs) {
			return FrameMappingResult.missing("NO_FRAME_WITHIN_TOLERANCE");
		}
		if (!frame.isValid()) {
			return FrameMappingResult.invalid(frame, frame.invalidReasonCode());
		}
		return FrameMappingResult.present(frame, syncErrorNs);
	}

	private static SourceFrame nearestFrame(List<SourceFrame> sourceFrames, Rational targetSourceNs) {
		var low = 0;
		var high = sourceFrames.size();
		while (low < high) {
			var middle = (low + high) >>> 1;
			if (Rational.whole(sourceFrames.get(middle).sourcePtsNs()).compareTo(targetSourceNs) < 0) {
				low = middle + 1;
			}
			else {
				high = middle;
			}
		}
		var after = low < sourceFrames.size() ? sourceFrames.get(low) : null;
		var before = low > 0 ? firstFrameAtPts(sourceFrames, low - 1) : null;
		if (before == null) {
			return after;
		}
		if (after == null) {
			return before;
		}
		var beforeDistance = Rational.whole(before.sourcePtsNs()).subtract(targetSourceNs).abs();
		var afterDistance = Rational.whole(after.sourcePtsNs()).subtract(targetSourceNs).abs();
		return beforeDistance.compareTo(afterDistance) <= 0 ? before : after;
	}

	private static SourceFrame firstFrameAtPts(List<SourceFrame> sourceFrames, int index) {
		var sourcePtsNs = sourceFrames.get(index).sourcePtsNs();
		while (index > 0 && sourceFrames.get(index - 1).sourcePtsNs() == sourcePtsNs) {
			index--;
		}
		return sourceFrames.get(index);
	}

}
