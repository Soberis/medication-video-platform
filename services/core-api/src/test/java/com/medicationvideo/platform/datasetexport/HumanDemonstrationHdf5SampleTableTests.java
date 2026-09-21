package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateAnnotationSnapshot;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.ApprovedGroupSnapshot;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.CameraView;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.Correspondence;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.CorrespondenceMember;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.Segment;
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

	private static final UUID REVISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");

	private static final UUID SCHEMA_ID = UUID.fromString("00000000-0000-0000-0000-000000000804");

	private static final UUID STREAM_1 = UUID.fromString("00000000-0000-0000-0000-000000000811");

	private static final UUID STREAM_2 = UUID.fromString("00000000-0000-0000-0000-000000000812");

	private static final UUID STREAM_3 = UUID.fromString("00000000-0000-0000-0000-000000000813");

	private static final UUID CLIP_1 = UUID.fromString("00000000-0000-0000-0000-000000000821");

	private static final UUID CLIP_2 = UUID.fromString("00000000-0000-0000-0000-000000000822");

	private static final UUID CLIP_3 = UUID.fromString("00000000-0000-0000-0000-000000000823");

	private static final UUID JOB_1 = UUID.fromString("00000000-0000-0000-0000-000000000831");

	private static final UUID JOB_2 = UUID.fromString("00000000-0000-0000-0000-000000000832");

	private static final UUID CANDIDATE_1 = UUID.fromString("00000000-0000-0000-0000-000000000841");

	private static final UUID CANDIDATE_2 = UUID.fromString("00000000-0000-0000-0000-000000000842");

	private static final UUID SEGMENT_1 = UUID.fromString("00000000-0000-0000-0000-000000000851");

	private static final UUID SEGMENT_2 = UUID.fromString("00000000-0000-0000-0000-000000000852");

	private static final UUID CORRESPONDENCE_1 = UUID.fromString("00000000-0000-0000-0000-000000000861");

	private static final UUID ACTION_CANDIDATE_1 = UUID.fromString("00000000-0000-0000-0000-000000000871");

	private static final UUID ACTION_CANDIDATE_2 = UUID.fromString("00000000-0000-0000-0000-000000000872");

	private static final String SHA_1 = "a".repeat(64);

	private static final String SHA_2 = "b".repeat(64);

	private static final String SHA_3 = "c".repeat(64);

	private static final String MODEL_SHA = "d".repeat(64);

	private static final String PROFILE_SHA = "e".repeat(64);

	private static final String RESULT_SHA = "f".repeat(64);

	@Test
	void keepsEqualLocalTimestampsFromDifferentCamerasAsSeparateLabeledSamples() throws Exception {
		var candidate1 = candidate(CANDIDATE_1, JOB_1, CLIP_1, 520, "RIGHT", "0.9500");
		var candidate2 = candidate(CANDIDATE_2, JOB_2, CLIP_2, 520, "LEFT", "0.8700");
		var service = service(candidate1, candidate2);

		var exportPath = service.download(PROJECT_ID, List.of(GROUP_ID),
				HumanDemonstrationHdf5ExportService.ExportOptions.v2(true, true));
		var bytes = Files.readAllBytes(exportPath);
		Files.deleteIfExists(exportPath);

		try (var file = HdfFile.fromBytes(bytes)) {
			var demo = (Group) file.getByPath("/data/demo_000000");
			var samples = (Group) file.getByPath("/data/demo_000000/samples");
			assertEquals("human-demonstration-hdf5.v2", file.getAttribute("schema_version").getData());
			assertEquals(2, samples.getAttribute("step_count").getData());
			assertArrayEquals(new int[] { 1, 2 },
					(int[]) file.getDatasetByPath("/data/demo_000000/samples/camera_number").getData());
			assertArrayEquals(new long[] { 520, 520 },
					(long[]) file.getDatasetByPath("/data/demo_000000/samples/frame_timestamp_ms").getData());
			assertArrayEquals(new long[] { 500, 300 },
					(long[]) file.getDatasetByPath("/data/demo_000000/samples/segment_start_ms").getData());
			assertArrayEquals(new String[] { "verify_dose", "verify_dose" },
					(String[]) file.getDatasetByPath("/data/demo_000000/samples/label").getData());
			assertArrayEquals(new int[] { 0, 1 },
					(int[]) file.getDatasetByPath("/data/demo_000000/samples/candidate_provenance/step_index")
						.getData());
			assertArrayEquals(new String[] { CANDIDATE_1.toString(), CANDIDATE_2.toString() },
					(String[]) file
						.getDatasetByPath("/data/demo_000000/samples/candidate_provenance/candidate_annotation_id")
						.getData());
			assertEquals(CLIP_1.toString(),
					samples.getByPath("sources/camera_01").getAttribute("clip_asset_id").getData());
			assertEquals(SHA_2, samples.getByPath("sources/camera_02").getAttribute("input_sha256").getData());
			assertFalse(samples.getAttributes().containsKey("input_sha256"));
			assertArrayEquals(new String[] { ACTION_CANDIDATE_1.toString(), ACTION_CANDIDATE_2.toString() },
					(String[]) file.getDatasetByPath("/data/demo_000000/annotations/source_candidate_id").getData());
			assertArrayEquals(new int[] { 0, 1 },
					(int[]) file.getDatasetByPath("/data/demo_000000/annotations/source_candidate_segment_index")
						.getData());
			assertArrayEquals(new String[] { "CONFIRMED" },
					(String[]) file.getDatasetByPath("/data/demo_000000/annotations/correspondences/status").getData());
			assertArrayEquals(new int[] { 1, 2 },
					(int[]) file.getDatasetByPath("/data/demo_000000/annotations/correspondences/camera_number")
						.getData());
			assertFalse(((Group) demo.getByPath("views")).getChildren().containsKey("camera_03"));
			assertFalse(((Group) samples.getByPath("sources")).getChildren().containsKey("camera_03"));
		}
	}

	@Test
	void writesCameraAndCandidateIdentityOnEachColumnarSampleRow() throws Exception {
		var candidate1 = candidate(CANDIDATE_1, JOB_1, CLIP_1, 520, "RIGHT", "0.9500");
		var candidate2 = candidate(CANDIDATE_2, JOB_2, CLIP_2, 520, "LEFT", "0.8700");
		var service = service(candidate1, candidate2);

		var exportPath = service.download(PROJECT_ID, List.of(GROUP_ID),
				HumanDemonstrationHdf5ExportService.ExportOptions.v2(false, false));
		var bytes = Files.readAllBytes(exportPath);
		Files.deleteIfExists(exportPath);

		try (var file = HdfFile.fromBytes(bytes)) {
			var samples = file.getByPath("/data/demo_000000/samples");
			assertEquals("columnar-table.v2", samples.getAttribute("layout").getData());
			assertArrayEquals(new int[] { 1, 2 },
					(int[]) file.getDatasetByPath("/data/demo_000000/samples/camera_number").getData());
			assertArrayEquals(new String[] { CANDIDATE_1.toString(), CANDIDATE_2.toString() },
					(String[]) file.getDatasetByPath("/data/demo_000000/samples/candidate_annotation_id").getData());
			assertArrayEquals(new String[] { "verify_dose", "verify_dose" },
					(String[]) file.getDatasetByPath("/data/demo_000000/samples/label").getData());
			assertArrayEquals(new String[] { ACTION_CANDIDATE_1.toString(), ACTION_CANDIDATE_2.toString() },
					(String[]) file.getDatasetByPath("/data/demo_000000/annotations/source_candidate_id").getData());
			assertFalse(((Group) file.getByPath("/data/demo_000000")).getChildren().containsKey("views"));
		}
	}

	@Test
	void rejectsExportWhenNoCurrentHandSamplesFallInsideAnyApprovedLocalInterval() throws Exception {
		var candidate1 = candidate(CANDIDATE_1, JOB_1, CLIP_1, 1200, "RIGHT", "0.9500");
		var candidate2 = candidate(CANDIDATE_2, JOB_2, CLIP_2, 1200, "LEFT", "0.8700");
		var service = service(candidate1, candidate2);

		var error = assertThrows(TrainingDataExportException.class,
				() -> service.download(PROJECT_ID, List.of(GROUP_ID)));

		assertEquals("HDF5_EXPORT_NOT_READY", error.getErrorCode());
	}

	private static HumanDemonstrationHdf5ExportService service(CandidateAnnotationSnapshot candidate1,
			CandidateAnnotationSnapshot candidate2) throws Exception {
		var approved = mock(ApprovedCaptureGroupAnnotationQueryService.class);
		var groups = mock(CaptureGroupViewQueryService.class);
		var candidates = mock(CandidateEvidenceQueryService.class);
		when(approved.findApproved(PROJECT_ID, List.of(GROUP_ID))).thenReturn(List.of(snapshot()));
		when(groups.get(PROJECT_ID, GROUP_ID)).thenReturn(group());
		when(candidates.findByClipAssets(PROJECT_ID, List.of(CLIP_1, CLIP_2))).thenReturn(
				List.of(evidence(CLIP_1, SHA_1, JOB_1, candidate1), evidence(CLIP_2, SHA_2, JOB_2, candidate2)));
		return new HumanDemonstrationHdf5ExportService(approved, groups, candidates, "synthetic");
	}

	private static ApprovedGroupSnapshot snapshot() {
		var correspondence = new Correspondence(CORRESPONDENCE_1, "CONFIRMED", null,
				List.of(new CorrespondenceMember(1, SEGMENT_1), new CorrespondenceMember(2, SEGMENT_2)));
		var segment1 = new Segment(SEGMENT_1, 500, 900, "verify_dose", CORRESPONDENCE_1, "CONFIRMED",
				List.of(ACTION_CANDIDATE_1));
		var segment2 = new Segment(SEGMENT_2, 300, 700, "verify_dose", CORRESPONDENCE_1, "CONFIRMED",
				List.of(ACTION_CANDIDATE_2));
		var camera1 = new CameraView(1, STREAM_1, CLIP_1, SHA_1, 1, 1, true, null, List.of(segment1));
		var camera2 = new CameraView(2, STREAM_2, CLIP_2, SHA_2, 1, 1, true, null, List.of(segment2));
		var excluded = new CameraView(3, STREAM_3, CLIP_3, SHA_3, 1, 1, false, "Different procedure", List.of());
		return new ApprovedGroupSnapshot(PROJECT_ID, GROUP_ID, UUID.randomUUID(), REVISION_ID, 1, SCHEMA_ID,
				"9".repeat(64), "8".repeat(64), "input-version", List.of(camera1, camera2, excluded),
				List.of(correspondence));
	}

	private static CaptureGroupFacts group() {
		return new CaptureGroupFacts(PROJECT_ID, GROUP_ID, CaptureGroupReceiptState.COMPLETE, 1, UUID.randomUUID(),
				false, List.of(view(STREAM_1, CLIP_1, 1, SHA_1), view(STREAM_2, CLIP_2, 2, SHA_2),
						view(STREAM_3, CLIP_3, 3, SHA_3)));
	}

	private static CaptureGroupView view(UUID streamId, UUID clipId, int camera, String sha) {
		return new CaptureGroupView(streamId, clipId, UUID.randomUUID(), camera, "CAMERA", "position", "INGESTED",
				"PASS", 1, 1, true, "UNKNOWN", sha, true);
	}

	private static CandidateEvidence evidence(UUID clipId, String assetSha, UUID jobId,
			CandidateAnnotationSnapshot candidate) {
		return new CandidateEvidence(clipId, assetSha, true,
				CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES, null, jobId, assetSha,
				"MEDIAPIPE_HAND_LANDMARKER", "v1", MODEL_SHA, PROFILE_SHA, UUID.randomUUID(), 1, UUID.randomUUID(),
				RESULT_SHA, "HAND_DETECTED", 1, candidate.frameTimestampMs(), candidate.frameTimestampMs(),
				"candidate-digest", null, List.of(candidate));
	}

	private static CandidateAnnotationSnapshot candidate(UUID candidateId, UUID jobId, UUID clipId, long timestampMs,
			String handedness, String score) throws Exception {
		var mapper = new ObjectMapper();
		var landmarks = mapper.readTree("[" + java.util.stream.IntStream.range(0, 21)
			.mapToObj(index -> "{\"x\":0.1,\"y\":0.2,\"z\":0.3}")
			.collect(java.util.stream.Collectors.joining(",")) + "]");
		var boundingBox = mapper.readTree("{\"x_min\":0.1,\"y_min\":0.1,\"x_max\":0.9,\"y_max\":0.9}");
		return new CandidateAnnotationSnapshot(candidateId, jobId, PROJECT_ID, clipId, timestampMs, 1280, 720, 0,
				handedness, new BigDecimal(score), boundingBox, landmarks, 21);
	}

}
