package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CaptureSyncEngineTests {

	private static final String SHA = "a".repeat(64);

	private static final UUID CAPTURE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID REVISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private final CaptureSyncEngine engine = new CaptureSyncEngine();

	@Test
	void noTrustedAnchorRejectsSynchronization() {
		var stream = stream(1, mapping(0, 0, null, false), List.of(frame(0, 0)));

		var exception = assertThrows(SynchronizationUnavailableException.class,
				() -> this.engine.buildFrameSlots(CAPTURE_ID, REVISION_ID, Rational.whole(1_000_000_000L), List.of(1),
						Map.of(1, stream), profile()));

		assertEquals(SynchronizationUnavailableException.TRUSTED_MAPPING_REQUIRED, exception.getErrorCode());
		assertEquals(1, exception.getCameraNumber());
	}

	@Test
	void streamMapKeyMustMatchTheStreamCameraNumberBeforeBuildingFrameSlots() {
		var stream = stream(3, mapping(0, 0, null, true), List.of(frame(0, 0)));

		var exception = assertThrows(IllegalArgumentException.class, () -> this.engine.buildFrameSlots(CAPTURE_ID,
				REVISION_ID, Rational.whole(1_000_000_000L), List.of(1, 3), Map.of(1, stream), profile()));

		assertTrue(exception.getMessage().contains("map camera number=1"));
		assertTrue(exception.getMessage().contains("stream camera number=3"));
	}

	@Test
	void oneAnchorIsOffsetOnlyAndTwoAnchorsCanMeasureDrift() {
		var offsetOnly = StreamTimeMapping.fromAnchors(List.of(new SyncAnchor(10, 100)), "MANUAL", "G0");
		assertNull(offsetOnly.driftPpm());
		assertEquals(100L, offsetOnly.mapSourceToCaptureTime(10));

		var drift = StreamTimeMapping.fromAnchors(
				List.of(new SyncAnchor(0, 0), new SyncAnchor(1_000_000_000L, 1_001_000_000L)), "TWO_ANCHOR_AFFINE",
				"G0");
		assertEquals(Rational.whole(1_000), drift.driftPpm());
		assertEquals(1_001_000_000L, drift.mapSourceToCaptureTime(1_000_000_000L));
		assertEquals(Rational.of(1_000_000_000_000L, 1_001), drift.mapCaptureToSourceTime(1_000_000_000L));
	}

	@Test
	void moreThanTwoAnchorsRequireAnApprovedFitInsteadOfDiscardingEvidence() {
		var anchors = List.of(new SyncAnchor(0, 0), new SyncAnchor(500_000_000L, 500_400_000L),
				new SyncAnchor(1_000_000_000L, 1_001_000_000L));

		var exception = assertThrows(IllegalArgumentException.class,
				() -> StreamTimeMapping.fromAnchors(anchors, "UNAPPROVED_MULTI_ANCHOR_FIT", "G0"));

		assertTrue(exception.getMessage().contains("approved fitting method"));
	}

	@Test
	void syncErrorIsSignedInCaptureDomain() {
		var stream = stream(1, mapping(0, 80_000_000L, null, true), List.of(frame(0, 0)));
		var positive = mapSingleFrame(0, stream, profile(80_000_000L));
		assertEquals(FrameState.PRESENT, positive.frameState());
		assertEquals(80_000_000L, positive.syncErrorNs());

		var negativeStream = stream(1, mapping(0, -80_000_000L, null, true), List.of(frame(0, 0)));
		var negative = mapSingleFrame(0, negativeStream, profile(80_000_000L));
		assertEquals(-80_000_000L, negative.syncErrorNs());
	}

	@Test
	void nearestTieBreakUsesEarlierPtsThenFrameIndexThenOrdinal() {
		var stream = stream(1, mapping(0, 0, null, true),
				List.of(frame(3, 120_000_000L), frame(2, 80_000_000L), frame(1, 80_000_000L)));
		var result = mapSingleFrame(100_000_000L, stream, profile());

		assertEquals(FrameState.PRESENT, result.frameState());
		assertEquals(80_000_000L, result.sourcePtsNs());
		assertEquals(1L, result.sourceFrameIndex());
		assertEquals(-20_000_000L, result.syncErrorNs());
	}

	@Test
	void toleranceBoundaryIsInclusiveAndExcessIsMissing() {
		var stream = stream(1, mapping(0, 0, null, true), List.of(frame(0, 80_000_000L)));
		assertEquals(FrameState.PRESENT, mapSingleFrame(100_000_000L, stream, profile()).frameState());
		var missing = mapSingleFrame(100_000_000L, stream, profile(19_999_999L));
		assertEquals(FrameState.MISSING, missing.frameState());
		assertEquals("NO_FRAME_WITHIN_TOLERANCE", missing.frameReasonCode());
	}

	@Test
	void invalidFrameIsNotMissing() {
		var stream = stream(1, mapping(0, 0, null, true), List.of(invalidFrame(0, 0, "DECODE_VALIDATION_FAILED")));
		var result = mapSingleFrame(0, stream, profile(0));

		assertEquals(FrameState.INVALID, result.frameState());
		assertEquals("DECODE_VALIDATION_FAILED", result.frameReasonCode());
		assertEquals(0L, result.sourcePtsNs());
	}

	@Test
	void missingCameraDoesNotRenumberTheRemainingCameras() {
		var streams = Map.of(1, stream(1, mapping(0, 0, null, true), List.of(frame(0, 0))), 3,
				stream(3, mapping(0, 0, null, true), List.of(frame(0, 0))));
		var slots = this.engine.buildFrameSlots(CAPTURE_ID, REVISION_ID, Rational.whole(1_000_000_000L),
				List.of(1, 2, 3), streams, profile());

		assertEquals(List.of(1, 2, 3), slots.stream().map(FrameSlot::cameraNumber).distinct().toList());
		assertEquals(FrameState.MISSING,
				slots.stream().filter(slot -> slot.cameraNumber() == 2).findFirst().orElseThrow().frameState());
		assertEquals(id("stream", 3),
				slots.stream().filter(slot -> slot.cameraNumber() == 3).findFirst().orElseThrow().cameraStreamId());
	}

	@Test
	void differentFpsUsesPtsRatherThanSyncFrameIndex() {
		var stream = stream(1, mapping(0, 0, null, true),
				List.of(frame(0, 0), frame(1, 40_000_000L), frame(2, 80_000_000L), frame(3, 120_000_000L)));
		var slots = this.engine.buildFrameSlots(CAPTURE_ID, REVISION_ID, Rational.whole(140_000_000L), List.of(1),
				Map.of(1, stream), profile());

		assertEquals(2L, slots.get(3).sourceFrameIndex());
		assertEquals(80_000_000L, slots.get(3).sourcePtsNs());
	}

	@Test
	void timelineUsesIndependentHalfEvenRounding() {
		var timeline = this.engine.buildTimeline(0, Rational.whole(3_600_000_000_000L), profile30Hz());

		assertEquals(108_000, timeline.size());
		assertEquals(3_599_966_666_667L, timeline.getLast());
		assertEquals(0L, new Rational(1, 2).roundHalfEvenLong());
		assertEquals(2L, new Rational(3, 2).roundHalfEvenLong());
		assertEquals(-2L, new Rational(-3, 2).roundHalfEvenLong());
	}

	@Test
	void frozenInputDigestIncludesCameraIdentityAndMapping() {
		var inputA = input(List.of(stream(1, mapping(0, 0, null, true), List.of(frame(0, 0)))));
		var inputB = input(List.of(stream(2, mapping(0, 0, null, true), List.of(frame(0, 0)))));

		assertFalse(inputA.manifestSha256(profile(), "CAPTURE_SYNC", "v1")
			.equals(inputB.manifestSha256(profile(), "CAPTURE_SYNC", "v1")));
	}

	private static SyncProfile profile() {
		return profile(20_000_000L);
	}

	private static SyncProfile profile(long allowedToleranceNs) {
		return SyncProfile.testOnly("TEST/G0", "v1", SHA, "CAPTURE_SYNC", "v1", Rational.of(30, 1),
				Rational.of(1_000_000_000L, 30), allowedToleranceNs, "TRUSTED_STREAM_TO_CAPTURE_MAPPING",
				"TRUSTED_ANCHOR_OFFSET_DRIFT");
	}

	private static SyncProfile profile30Hz() {
		return profile();
	}

	private static CaptureSyncInput input(List<SyncStream> streams) {
		var camera = streams.getFirst().cameraNumber();
		return new CaptureSyncInput(CAPTURE_ID, CAPTURE_ID, UUID.fromString("00000000-0000-0000-0000-000000000003"),
				"capture-group", SHA,
				List.of(new ExpectedCamera(
						UUID.nameUUIDFromBytes(("expected:" + camera).getBytes(StandardCharsets.UTF_8)), camera)),
				streams);
	}

	private FrameSlot mapSingleFrame(long captureTimestampNs, SyncStream stream, SyncProfile profile) {
		return this.engine
			.buildFrameSlots(CAPTURE_ID, REVISION_ID, captureTimestampNs, Rational.whole(1),
					List.of(stream.cameraNumber()), Map.of(stream.cameraNumber(), stream), profile)
			.getFirst();
	}

	private static SyncStream stream(int cameraNumber, StreamTimeMapping mapping, List<SourceFrame> frames) {
		return new SyncStream(cameraNumber, id("stream", cameraNumber), id("asset", cameraNumber), SHA,
				id("report", cameraNumber), SHA, mapping, frames);
	}

	private static UUID id(String prefix, int cameraNumber) {
		return UUID.nameUUIDFromBytes((prefix + ":" + cameraNumber).getBytes(StandardCharsets.UTF_8));
	}

	private static StreamTimeMapping mapping(long sourceNs, long captureNs, Rational driftPpm, boolean trusted) {
		return new StreamTimeMapping(sourceNs, captureNs, driftPpm, "TEST", "SYNTHETIC", driftPpm == null ? 1 : 2,
				trusted);
	}

	private static SourceFrame frame(long index, long ptsNs) {
		return SourceFrame.valid(index, index, ptsNs, 0);
	}

	private static SourceFrame invalidFrame(long index, long ptsNs, String reason) {
		return SourceFrame.invalid(index, index, ptsNs, 0, reason);
	}

}
