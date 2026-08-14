package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MinimalTrainingDataExportServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");

	private static final UUID VIDEO_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");

	private static final UUID REVISION_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");

	private static final UUID CANDIDATE_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MinimalTrainingDataExportService service = new MinimalTrainingDataExportService(
			mock(JdbcTemplate.class), JSON, "synthetic");

	@Test
	void alignsOneCandidateHandWithHumanIntervalAndWritesParquetProvenance() {
		var built = this.service.buildTrainingRows(List.of(eligibleVideo()), List.of(candidate(120, "RIGHT")));

		assertEquals(1, built.rows().size());
		assertEquals(0, built.unlabeledCandidateObservationCount());
		var row = built.rows().getFirst();
		assertEquals(100, row.actionStartMs());
		assertEquals(400, row.actionEndMs());
		assertTrue(row.actionStartMs() <= row.timestampMs());
		assertTrue(row.timestampMs() < row.actionEndMs());
		assertEquals("prepare_dose", row.actionLabel());
		assertEquals("RIGHT", row.handedness());
		assertEquals("MEDIAPIPE_HAND_LANDMARKER", row.algorithmKey());
		assertEquals("mediapipe-hand-landmarker.v1", row.algorithmVersion());
		assertEquals("e".repeat(64), row.modelSha256());
		assertEquals("f".repeat(64), row.profileSha256());
		assertTrue(row.landmarks().size() == 63);

		var parquet = MinimalTrainingDataExportService.writeParquet(built.rows());
		assertTrue(parquet.length > 4);
		assertEquals("PAR1", new String(parquet, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
		assertEquals("normalized_image_xy_and_mediapipe_relative_z",
				MinimalTrainingDataExportService.PARQUET_METADATA.get("coordinate_system"));
		assertEquals("false", MinimalTrainingDataExportService.PARQUET_METADATA.get("hand_geometry_is_ground_truth"));
		assertEquals("human_annotation_revision",
				MinimalTrainingDataExportService.PARQUET_METADATA.get("action_label_source"));
		assertEquals("human_annotation_revision.annotation_document.segments",
				MinimalTrainingDataExportService.PARQUET_METADATA.get("action_interval_source"));
		assertEquals("candidate_annotation.handedness_score",
				MinimalTrainingDataExportService.PARQUET_METADATA.get("handedness_score_source"));
		assertTrue(!MinimalTrainingDataExportService.PARQUET_METADATA.containsKey("confidence_source"));
	}

	@Test
	void excludesCandidatesOutsideHumanIntervalsWithoutInventingAnotherHand() {
		var built = this.service.buildTrainingRows(List.of(eligibleVideo()), List.of(candidate(500, "LEFT")));

		assertTrue(built.rows().isEmpty());
		assertEquals(1, built.unlabeledCandidateObservationCount());
	}

	@Test
	void includesCandidateAtHumanIntervalStartAndExcludesEnd() {
		var atStart = this.service.buildTrainingRows(List.of(eligibleVideo()), List.of(candidate(100, "LEFT")));
		var atEnd = this.service.buildTrainingRows(List.of(eligibleVideo()), List.of(candidate(400, "LEFT")));

		assertEquals(1, atStart.rows().size());
		assertEquals(0, atStart.unlabeledCandidateObservationCount());
		assertTrue(atEnd.rows().isEmpty());
		assertEquals(1, atEnd.unlabeledCandidateObservationCount());
	}

	@Test
	void rejectsCleaningFailureBeforeAnyRowCanBeBuilt() {
		var eligible = eligibleVideo();
		var ineligible = new MinimalTrainingDataExportService.EligibleVideo(eligible.videoId(), eligible.contentType(),
				eligible.assetStatus(), eligible.assetDeleted(), eligible.assetSha256(), eligible.qualityDecision(),
				eligible.qualityReviewVersion(), eligible.annotationRevisionId(), eligible.annotationTaskId(),
				eligible.revisionNumber(), eligible.annotationDocument(), eligible.documentSha256(),
				eligible.labelSchemaId(), eligible.labelSchemaDigest(), eligible.annotationReviewId(),
				eligible.annotationReviewDecision(), eligible.cleaningResultId(), eligible.cleaningRunId(), "WARNING",
				eligible.cleaningRunStatus(), eligible.cleaningRuleSetId(), eligible.ruleSetDigest());

		assertThrows(TrainingDataExportException.class,
				() -> this.service.buildTrainingRows(List.of(ineligible), List.of(candidate(120, "LEFT"))));
	}

	@Test
	void rejectsAnIncompleteCandidateInsteadOfFillingMissingLandmarks() {
		var candidate = new MinimalTrainingDataExportService.CandidateObservation(VIDEO_ID, REVISION_ID, CANDIDATE_ID,
				120, "MEDIAPIPE_HAND_LANDMARKER", "mediapipe-hand-landmarker.v1", 0, "LEFT", 0.75, JSON.readTree("[]"),
				"e".repeat(64), "f".repeat(64), 20);

		assertThrows(TrainingDataExportException.class,
				() -> this.service.buildTrainingRows(List.of(eligibleVideo()), List.of(candidate)));
	}

	private static MinimalTrainingDataExportService.EligibleVideo eligibleVideo() {
		return new MinimalTrainingDataExportService.EligibleVideo(VIDEO_ID, "video/mp4", "INGESTED", false,
				"a".repeat(64), "QUALIFIED", 1, REVISION_ID, UUID.fromString("81000000-0000-4000-8000-000000000001"), 1,
				JSON.readTree(
						"""
								{"schema_version":"annotation-document.v1","segments":[{"start_ms":100,"end_ms":400,"label":"prepare_dose"}]}
								"""),
				"b".repeat(64), UUID.fromString("10000000-0000-0000-0000-000000000001"), "c".repeat(64),
				UUID.fromString("82000000-0000-4000-8000-000000000001"), "APPROVED",
				UUID.fromString("83000000-0000-4000-8000-000000000001"),
				UUID.fromString("84000000-0000-4000-8000-000000000001"), "PASS", "SUCCEEDED",
				UUID.fromString("30000000-0000-0000-0000-000000000001"), "d".repeat(64));
	}

	private static MinimalTrainingDataExportService.CandidateObservation candidate(long timestampMs,
			String handedness) {
		return new MinimalTrainingDataExportService.CandidateObservation(VIDEO_ID, REVISION_ID, CANDIDATE_ID,
				timestampMs, "MEDIAPIPE_HAND_LANDMARKER", "mediapipe-hand-landmarker.v1", 0, handedness, 0.75,
				landmarks(), "e".repeat(64), "f".repeat(64), 21);
	}

	private static JsonNode landmarks() {
		var points = IntStream.range(0, 21)
			.mapToObj(index -> "{\"x\":" + index / 100.0 + ",\"y\":0.2,\"z\":-0.01}")
			.collect(Collectors.joining(","));
		return JSON.readTree("[" + points + "]");
	}

}
