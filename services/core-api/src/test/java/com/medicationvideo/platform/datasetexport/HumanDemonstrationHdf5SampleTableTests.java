package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateAnnotationSnapshot;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.annotationreview.ApprovedHumanAnnotationQueryService;
import com.medicationvideo.platform.annotationreview.HumanAnnotationExportSegment;
import com.medicationvideo.platform.assetcapture.CaptureGroupReceiptState;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;

import io.jhdf.HdfFile;
import io.jhdf.api.Group;
import tools.jackson.databind.ObjectMapper;

class HumanDemonstrationHdf5SampleTableTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");

	private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");

	private static final UUID STREAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");

	private static final UUID CLIP_ID = UUID.fromString("00000000-0000-0000-0000-000000000804");

	private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000805");

	private static final String SHA = "a".repeat(64);

	private static final String MODEL_SHA = "b".repeat(64);

	private static final String PROFILE_SHA = "c".repeat(64);

	private static final String RESULT_SHA = "d".repeat(64);

	@Test
	void alignsPrimaryVideoMediapipeSampleWithApprovedActionLabel() throws Exception {
		var service = service(candidate(520));

		var bytes = service.download(PROJECT_ID, List.of(GROUP_ID));

		try (var file = HdfFile.fromBytes(bytes)) {
			var path = "/data/demo_000000/samples/";
			assertEquals("columnar-table.v1",
					file.getByPath("/data/demo_000000/samples").getAttribute("layout").getData());
			assertEquals("verify_dose", ((String[]) file.getDatasetByPath(path + "label").getData())[0]);
			assertEquals(500L, ((long[]) file.getDatasetByPath(path + "segment_start_ms").getData())[0]);
			assertEquals(900L, ((long[]) file.getDatasetByPath(path + "segment_end_ms").getData())[0]);
			assertEquals(520L, ((long[]) file.getDatasetByPath(path + "frame_timestamp_ms").getData())[0]);
			var landmarks = (float[][][]) file.getDatasetByPath(path + "landmarks").getData();
			assertEquals(1, landmarks.length);
			assertEquals(21, landmarks[0].length);
			assertEquals(3, landmarks[0][0].length);
			var samplesGroup = file.getByPath("/data/demo_000000/samples");
			assertEquals(MODEL_SHA, samplesGroup.getAttribute("model_sha256").getData());
			assertFalse(samplesGroup.getAttributes().containsKey("input_sha256"));
			assertFalse(samplesGroup.getAttributes().containsKey("profile_sha256"));
			assertFalse(samplesGroup.getAttributes().containsKey("result_sha256"));
			var annotationsGroup = file.getByPath("/data/demo_000000/annotations");
			assertEquals("f".repeat(64), annotationsGroup.getAttribute("document_sha256").getData());
			assertNotNull(annotationsGroup.getAttribute("annotation_revision_id"));
		}
	}

	@Test
	void exportsV2DualHandTensorLayoutAndPrunesRedundantColumns() throws Exception {
		var service = service(candidate(520));

		var bytes = service.download(PROJECT_ID, List.of(GROUP_ID),
				HumanDemonstrationHdf5ExportService.ExportOptions.V2_DEFAULT);

		try (var file = HdfFile.fromBytes(bytes)) {
			assertEquals("human-demonstration-hdf5.v2", file.getAttribute("schema_version").getData());
			var samples = file.getByPath("/data/demo_000000/samples");
			assertEquals("dual-hand-tensor.v2", samples.getAttribute("layout").getData());
			assertEquals(1280, samples.getAttribute("image_width").getData());
			assertEquals(720, samples.getAttribute("image_height").getData());
			assertEquals(1, samples.getAttribute("step_count").getData());

			var path = "/data/demo_000000/samples/";
			assertEquals("verify_dose", ((String[]) file.getDatasetByPath(path + "label").getData())[0]);
			assertEquals(520L, ((long[]) file.getDatasetByPath(path + "frame_timestamp_ms").getData())[0]);

			var landmarks = (float[][][][]) file.getDatasetByPath(path + "landmarks").getData();
			assertEquals(1, landmarks.length);
			assertEquals(2, landmarks[0].length);
			assertEquals(21, landmarks[0][0].length);
			assertEquals(3, landmarks[0][0][0].length);

			var handMask = (int[][]) file.getDatasetByPath(path + "hand_mask").getData();
			assertEquals(0, handMask[0][0]);
			assertEquals(1, handMask[0][1]);

			var scores = (double[][]) file.getDatasetByPath(path + "handedness_score").getData();
			assertEquals(0.0, scores[0][0]);
			assertEquals(0.95, scores[0][1]);

			var sampleDatasets = ((Group) samples).getChildren();
			assertFalse(sampleDatasets.containsKey("image_width"));
			assertFalse(sampleDatasets.containsKey("image_height"));
			assertFalse(sampleDatasets.containsKey("segment_start_ms"));
			assertFalse(sampleDatasets.containsKey("segment_end_ms"));

			var demo = (Group) file.getByPath("/data/demo_000000");
			assertFalse(demo.getChildren().containsKey("views"));
			assertEquals(false, file.getAttribute("contains_raw_candidates").getData());
		}
	}

	@Test
	void exportsV2CompactTableLayoutWhenRequested() throws Exception {
		var service = service(candidate(520));

		var bytes = service.download(PROJECT_ID, List.of(GROUP_ID),
				HumanDemonstrationHdf5ExportService.ExportOptions.v2(false, false));

		try (var file = HdfFile.fromBytes(bytes)) {
			assertEquals("human-demonstration-hdf5.v2", file.getAttribute("schema_version").getData());
			var samples = file.getByPath("/data/demo_000000/samples");
			assertEquals("columnar-table.v2", samples.getAttribute("layout").getData());
			assertEquals(1280, samples.getAttribute("image_width").getData());
			assertEquals(720, samples.getAttribute("image_height").getData());

			var path = "/data/demo_000000/samples/";
			var landmarks = (float[][][]) file.getDatasetByPath(path + "landmarks").getData();
			assertEquals(1, landmarks.length);
			assertEquals(21, landmarks[0].length);
			assertEquals(3, landmarks[0][0].length);

			var sampleDatasets = ((Group) samples).getChildren();
			assertFalse(sampleDatasets.containsKey("image_width"));
			assertFalse(sampleDatasets.containsKey("segment_start_ms"));
		}
	}

	@Test
	void rejectsAnApprovedIntervalWhenNoMediapipeSampleFallsInsideIt() throws Exception {
		var service = service(candidate(1200));

		var error = assertThrows(TrainingDataExportException.class,
				() -> service.download(PROJECT_ID, List.of(GROUP_ID)));

		assertEquals("HDF5_EXPORT_NOT_READY", error.getErrorCode());
	}

	private static HumanDemonstrationHdf5ExportService service(CandidateAnnotationSnapshot candidate) throws Exception {
		var approved = mock(ApprovedHumanAnnotationQueryService.class);
		var groups = mock(CaptureGroupViewQueryService.class);
		var candidates = mock(CandidateEvidenceQueryService.class);
		when(approved.findApprovedSegments(PROJECT_ID, List.of(GROUP_ID))).thenReturn(List.of(segment()));
		when(groups.get(PROJECT_ID, GROUP_ID)).thenReturn(group());
		when(candidates.findByClipAssets(PROJECT_ID, List.of(CLIP_ID))).thenReturn(List.of(evidence(candidate)));
		return new HumanDemonstrationHdf5ExportService(approved, groups, candidates, "synthetic");
	}

	private static HumanAnnotationExportSegment segment() {
		return new HumanAnnotationExportSegment(GROUP_ID, UUID.randomUUID(), UUID.randomUUID(), 1, STREAM_ID, CLIP_ID,
				1, SHA, "annotation-document.v1", UUID.randomUUID(), "e".repeat(64), "input-v1", 500, 900,
				"verify_dose", null, "f".repeat(64), Instant.parse("2026-09-07T00:00:00Z"), UUID.randomUUID(),
				"APPROVED", Instant.parse("2026-09-07T00:01:00Z"));
	}

	private static CaptureGroupFacts group() {
		var view = new CaptureGroupView(STREAM_ID, CLIP_ID, UUID.randomUUID(), 1, "PRIMARY", "front", "INGESTED",
				"PASS", 1, 1, true, "UNKNOWN", SHA, true);
		return new CaptureGroupFacts(PROJECT_ID, GROUP_ID, CaptureGroupReceiptState.COMPLETE, 1, UUID.randomUUID(),
				false, List.of(view));
	}

	private static CandidateEvidence evidence(CandidateAnnotationSnapshot candidate) {
		return new CandidateEvidence(CLIP_ID, SHA, true,
				CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES, null, JOB_ID, SHA,
				"MEDIAPIPE_HAND_LANDMARKER", "v1", MODEL_SHA, PROFILE_SHA, UUID.randomUUID(), 1, UUID.randomUUID(),
				RESULT_SHA, "HAND_DETECTED", 1, candidate.frameTimestampMs(), candidate.frameTimestampMs(),
				"candidate-digest", null, List.of(candidate));
	}

	private static CandidateAnnotationSnapshot candidate(long timestampMs) throws Exception {
		var mapper = new ObjectMapper();
		var landmarks = mapper.readTree("[" + java.util.stream.IntStream.range(0, 21)
			.mapToObj(index -> "{\"x\":0.1,\"y\":0.2,\"z\":0.3}")
			.collect(java.util.stream.Collectors.joining(",")) + "]");
		var boundingBox = mapper.readTree("{\"x_min\":0.1,\"y_min\":0.1,\"x_max\":0.9,\"y_max\":0.9}");
		return new CandidateAnnotationSnapshot(UUID.randomUUID(), JOB_ID, PROJECT_ID, CLIP_ID, timestampMs, 1280, 720,
				0, "RIGHT", new BigDecimal("0.95"), boundingBox, landmarks, 21);
	}

}
