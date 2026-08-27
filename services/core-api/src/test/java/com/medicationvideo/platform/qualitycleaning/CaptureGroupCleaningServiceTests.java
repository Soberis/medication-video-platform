package com.medicationvideo.platform.qualitycleaning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.EvaluationCommand;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.Interval;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.SupportIntervalMapping;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.ViewInput;

class CaptureGroupCleaningServiceTests {

	private static final UUID GROUP_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");

	private static final UUID GROUP_QUALITY_ID = UUID.fromString("c0000000-0000-4000-8000-000000000002");

	@Test
	void reviewMustBeApprovedBeforeAnEventCanPassCleaning() {
		var result = CaptureGroupCleaningService
			.evaluateEligibility(command(false, "QUALIFIED", 1, Set.of(), Map.of()));

		assertEquals(CleaningOutcome.REJECT, result.outcome());
		assertTrue(result.reasonCodes().contains("REVIEW_NOT_APPROVED"));
	}

	@Test
	void qualifiedWithExclusionsPassesWhileRejectedViewRemainsRetainedAndExcluded() {
		var result = CaptureGroupCleaningService
			.evaluateEligibility(command(true, "QUALIFIED_WITH_EXCLUSIONS", 5, Set.of(2), Map.of()));

		assertEquals(CleaningOutcome.PASS, result.outcome());
		assertEquals(4, result.participatingViews().size());
		assertEquals(1, result.excludedViews().size());
		assertEquals(true, result.excludedViews().getFirst().get("retained"));
		assertEquals(false, result.excludedViews().getFirst().get("downstream_included"));
		assertEquals("VIEW_QUALITY_NOT_QUALIFIED", result.excludedViews().getFirst().get("exclusion_reason"));
	}

	@Test
	void oneViewGroupUsesThePrimaryViewAsTheOnlyParticipant() {
		var result = CaptureGroupCleaningService.evaluateEligibility(command(true, "QUALIFIED", 1, Set.of(), Map.of()));

		assertEquals(CleaningOutcome.PASS, result.outcome());
		assertEquals(1, result.participatingViews().size());
		assertEquals(0, result.excludedViews().size());
	}

	@Test
	void fiveViewGroupKeepsAllQualifiedViewsInTheEventEvidence() {
		var result = CaptureGroupCleaningService.evaluateEligibility(command(true, "QUALIFIED", 5, Set.of(), Map.of()));

		assertEquals(CleaningOutcome.PASS, result.outcome());
		assertEquals(5, result.viewEvidence().size());
		assertEquals(5, result.participatingViews().size());
	}

	@Test
	void primaryIntervalIsAuthoritativeAndHalfOpen() {
		var result = CaptureGroupCleaningService.evaluateEligibility(command(true, "QUALIFIED", 1, Set.of(), Map.of()));

		assertEquals(List.of(Map.of("start_ms", 100L, "end_ms", 400L, "label", "prepare_dose")),
				result.primaryInterval());
		var primary = result.participatingViews().getFirst();
		assertEquals("PRIMARY_AUTHORITATIVE", primary.get("interval_source"));
		assertEquals(true, primary.get("action_aligned_interval"));
	}

	@Test
	void reliableSupportingViewMayUseOnlyValidatedVersionedMapping() {
		var supportAssetId = clipAsset(2);
		var mapping = new SupportIntervalMapping(true, "mapping.v1", "synthetic-validated-provenance",
				List.of(new Interval(120, 420)));
		var result = CaptureGroupCleaningService
			.evaluateEligibility(command(true, "QUALIFIED", 2, Set.of(), Map.of(supportAssetId, mapping)));

		var support = result.participatingViews()
			.stream()
			.filter(view -> Integer.valueOf(2).equals(view.get("camera_number")))
			.findFirst()
			.orElseThrow();
		assertEquals("VALIDATED_VERSIONED_MAPPING", support.get("interval_source"));
		assertEquals(List.of(Map.of("start_ms", 120L, "end_ms", 420L)), support.get("interval"));
		assertEquals("mapping.v1", result.mappingProvenance().getFirst().get("mapping_version"));
	}

	@Test
	void approximateAndUnreliableSupportingViewsNeverReceiveFakeActionIntervals() {
		var approximate = new ViewInput(UUID.randomUUID(), clipAsset(2), 2, true, "INGESTED", "PASS", 1, 1, true,
				"QUALIFIED", 1, "WARNING");
		var unreliable = new ViewInput(UUID.randomUUID(), clipAsset(3), 3, true, "INGESTED", "PASS", 1, 1, true,
				"QUALIFIED", 1, "REJECT");
		var result = CaptureGroupCleaningService
			.evaluateEligibility(commandWithViews(true, "QUALIFIED", List.of(primary(), approximate, unreliable),
					Map.of(clipAsset(2),
							new SupportIntervalMapping(true, "mapping.v1", "should-not-be-used",
									List.of(new Interval(120, 420))),
							clipAsset(3), new SupportIntervalMapping(true, "mapping.v1", "should-not-be-used",
									List.of(new Interval(120, 420))))));

		assertEquals(0, result.mappingProvenance().size());
		assertEquals("EVENT_LEVEL_EVIDENCE", result.participatingViews().get(1).get("interval_source"));
		assertEquals("EVENT_LEVEL_EVIDENCE", result.participatingViews().get(2).get("interval_source"));
		assertNull(result.participatingViews().get(1).get("interval"));
		assertNull(result.participatingViews().get(2).get("interval"));
	}

	@Test
	void nonAdmittingGroupQualityCannotPassEventCleaning() {
		var result = CaptureGroupCleaningService
			.evaluateEligibility(command(true, "NEEDS_REVIEW", 1, Set.of(), Map.of()));

		assertEquals(CleaningOutcome.REJECT, result.outcome());
		assertTrue(result.reasonCodes().contains("GROUP_QUALITY_NOT_ADMITTING"));
	}

	private static EvaluationCommand command(boolean approvedReview, String groupQualityResult, int viewCount,
			Set<Integer> rejectedCameraNumbers, Map<UUID, SupportIntervalMapping> mappings) {
		var views = IntStream.rangeClosed(1, viewCount)
			.mapToObj(cameraNumber -> view(cameraNumber, rejectedCameraNumbers.contains(cameraNumber), "PASS"))
			.toList();
		return commandWithViews(approvedReview, groupQualityResult, views, mappings);
	}

	private static EvaluationCommand commandWithViews(boolean approvedReview, String groupQualityResult,
			List<ViewInput> views, Map<UUID, SupportIntervalMapping> mappings) {
		return new EvaluationCommand(UUID.fromString("c0000000-0000-4000-8000-000000000010"), GROUP_ID,
				UUID.fromString("c0000000-0000-4000-8000-000000000011"),
				UUID.fromString("c0000000-0000-4000-8000-000000000012"), approvedReview, "review.admin",
				Instant.parse("2026-08-01T01:00:00Z"), GROUP_QUALITY_ID, groupQualityResult, 1, clipAsset(1), 1,
				List.of(new CaptureGroupCleaningService.SegmentSnapshot(100, 400, "prepare_dose")), views, mappings,
				List.of(Map.of("algorithm_key", "MEDIAPIPE_HAND_LANDMARKER", "model_sha256", "a".repeat(64))),
				"review.admin", "PROJECT_MEMBERSHIP_ADMIN");
	}

	private static ViewInput primary() {
		return view(1, false, "PASS");
	}

	private static ViewInput view(int cameraNumber, boolean rejected, String syncStatus) {
		return new ViewInput(UUID.nameUUIDFromBytes(("stream-" + cameraNumber).getBytes(StandardCharsets.UTF_8)),
				clipAsset(cameraNumber), cameraNumber, true, "INGESTED", "PASS", 1, 1, true,
				rejected ? "REJECTED" : "QUALIFIED", 1, syncStatus);
	}

	private static UUID clipAsset(int cameraNumber) {
		return UUID.nameUUIDFromBytes(("clip-" + cameraNumber).getBytes(StandardCharsets.UTF_8));
	}

}
