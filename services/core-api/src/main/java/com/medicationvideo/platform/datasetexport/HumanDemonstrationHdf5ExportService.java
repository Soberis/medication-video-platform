package com.medicationvideo.platform.datasetexport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateAnnotationSnapshot;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.annotationreview.HumanAnnotationExportSegment;
import com.medicationvideo.platform.annotationreview.ApprovedHumanAnnotationQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;

import io.jhdf.HdfFile;
import io.jhdf.api.WritableGroup;
import tools.jackson.databind.JsonNode;

/**
 * Builds the compact P0 HDF5 interchange file from already approved human labels and
 * current MediaPipe facts.
 */
@Service
class HumanDemonstrationHdf5ExportService {

	static final String SCHEMA_VERSION = "human-demonstration-hdf5.v1";

	static final String MEDIA_TYPE = "application/x-hdf5";

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private final ApprovedHumanAnnotationQueryService approvedHumanAnnotationQueryService;

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CandidateEvidenceQueryService candidateEvidenceQueryService;

	private final String dataProfile;

	HumanDemonstrationHdf5ExportService(ApprovedHumanAnnotationQueryService approvedHumanAnnotationQueryService,
			CaptureGroupViewQueryService captureGroupViewQueryService,
			CandidateEvidenceQueryService candidateEvidenceQueryService,
			@Value("${platform.training-data-export.data-profile:disabled}") String dataProfile) {
		this.approvedHumanAnnotationQueryService = approvedHumanAnnotationQueryService;
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.candidateEvidenceQueryService = candidateEvidenceQueryService;
		this.dataProfile = dataProfile == null ? "" : dataProfile.trim().toLowerCase(java.util.Locale.ROOT);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	byte[] download(UUID projectId, List<UUID> captureGroupIds) {
		requireSyntheticProfile();
		var selectedIds = captureGroupIds == null ? List.<UUID>of() : List.copyOf(captureGroupIds);
		var segments = this.approvedHumanAnnotationQueryService.findApprovedSegments(projectId, selectedIds);
		if (segments.isEmpty()) {
			throw unavailable("No current approved human annotation is ready for HDF5 export.");
		}
		var segmentsByGroup = segments.stream()
			.collect(Collectors.groupingBy(HumanAnnotationExportSegment::captureGroupId,
					Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
		var groups = segmentsByGroup.keySet()
			.stream()
			.sorted(Comparator.comparing(UUID::toString))
			.map(groupId -> this.captureGroupViewQueryService.get(projectId, groupId))
			.toList();
		Path output = null;
		try {
			output = Files.createTempFile("human-demonstration-", ".hdf5");
			try (var file = HdfFile.write(output)) {
				file.putAttribute("schema_version", SCHEMA_VERSION);
				file.putAttribute("project_id", projectId.toString());
				file.putAttribute("export_scope", selectedIds.isEmpty() ? "project" : "selection");
				file.putAttribute("contains_mp4_bytes", false);
				file.putAttribute("contains_robot_actions", false);
				var data = file.putGroup("data");
				for (var index = 0; index < groups.size(); index += 1) {
					writeEpisode(data.putGroup("demo_%06d".formatted(index)), groups.get(index),
							segmentsByGroup.get(groups.get(index).captureGroupId()), projectId);
				}
			}
			return Files.readAllBytes(output);
		}
		catch (IOException exception) {
			throw new TrainingDataExportException("HDF5_EXPORT_FAILED", "The HDF5 export could not be created.", 500);
		}
		finally {
			if (output != null) {
				try {
					Files.deleteIfExists(output);
				}
				catch (IOException ignored) {
					// Temporary output contains only the already authorized synthetic
					// export snapshot.
				}
			}
		}
	}

	static String fileName(List<UUID> captureGroupIds, Instant generatedAt) {
		var scope = captureGroupIds == null || captureGroupIds.isEmpty() ? "project" : captureGroupIds.size() == 1
				? "event_" + captureGroupIds.getFirst().toString().substring(0, 8) : "selection";
		return "human_demonstration_hdf5_v1_%s_%s.hdf5".formatted(scope, FILE_TIMESTAMP.format(generatedAt));
	}

	private void writeEpisode(WritableGroup episode, CaptureGroupFacts group,
			List<HumanAnnotationExportSegment> segments, UUID projectId) {
		if (group == null || !projectId.equals(group.projectId()) || group.views() == null) {
			throw invalid("The current CaptureGroup projection is incomplete.");
		}
		var orderedSegments = segments.stream()
			.sorted(Comparator.comparingLong(HumanAnnotationExportSegment::segmentStartMs)
				.thenComparingLong(HumanAnnotationExportSegment::segmentEndMs))
			.toList();
		var first = orderedSegments.getFirst();
		episode.putAttribute("capture_group_id", group.captureGroupId().toString());
		episode.putAttribute("primary_clip_asset_id", first.primaryClipAssetId().toString());
		episode.putAttribute("primary_camera_number", first.primaryCameraNumber());
		episode.putAttribute("primary_clip_asset_sha256", first.clipAssetSha256());
		episode.putAttribute("annotation_revision_id", first.annotationRevisionId().toString());
		episode.putAttribute("label_schema_id", first.labelSchemaId().toString());
		episode.putAttribute("label_schema_digest", first.labelSchemaDigest());
		var annotations = episode.putGroup("annotations");
		annotations.putDataset("segment_start_ms",
				orderedSegments.stream().mapToLong(HumanAnnotationExportSegment::segmentStartMs).toArray());
		annotations.putDataset("segment_end_ms",
				orderedSegments.stream().mapToLong(HumanAnnotationExportSegment::segmentEndMs).toArray());
		annotations.putDataset("label",
				orderedSegments.stream().map(HumanAnnotationExportSegment::label).toArray(String[]::new));
		annotations.putDataset("annotation_revision_id",
				orderedSegments.stream()
					.map(segment -> segment.annotationRevisionId().toString())
					.toArray(String[]::new));
		annotations.putDataset("document_sha256",
				orderedSegments.stream().map(HumanAnnotationExportSegment::documentSha256).toArray(String[]::new));

		var views = group.views()
			.stream()
			.filter(CaptureGroupView::present)
			.filter(CaptureGroupView::enabledForHandDetection)
			.filter(CaptureGroupView::assetAvailable)
			.filter(view -> "INGESTED".equals(view.mediaStatus()))
			.toList();
		var evidenceByAsset = this.candidateEvidenceQueryService
			.findByClipAssets(projectId, views.stream().map(CaptureGroupView::clipAssetId).toList())
			.stream()
			.collect(Collectors.toMap(CandidateEvidence::clipAssetId, evidence -> evidence, (left, right) -> {
				throw invalid("The current MediaPipe evidence contains duplicate ClipAsset entries.");
			}));
		var viewGroup = episode.putGroup("views");
		for (var view : views) {
			writeView(viewGroup.putGroup("camera_%02d".formatted(view.cameraNumber())), view,
					evidenceByAsset.get(view.clipAssetId()), projectId);
		}
	}

	private static void writeView(WritableGroup output, CaptureGroupView view, CandidateEvidence evidence,
			UUID projectId) {
		if (view.cameraStreamId() == null || view.clipAssetId() == null || view.cameraNumber() < 1
				|| view.clipAssetSha256() == null || !view.clipAssetSha256().matches("^[a-f0-9]{64}$")
				|| evidence == null || !evidence.provenanceValid() || !view.clipAssetId().equals(evidence.clipAssetId())
				|| !view.clipAssetSha256().equals(evidence.clipAssetSha256()) || evidence.executionStatus() == null) {
			throw invalid("The current MediaPipe evidence cannot be linked to its source video.");
		}
		output.putAttribute("camera_stream_id", view.cameraStreamId().toString());
		output.putAttribute("camera_number", view.cameraNumber());
		output.putAttribute("clip_asset_id", view.clipAssetId().toString());
		output.putAttribute("clip_asset_sha256", view.clipAssetSha256());
		output.putAttribute("detection_status", evidence.executionStatus());
		if (!CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES
			.equals(evidence.executionStatus())) {
			return;
		}
		if (evidence.aiJobId() == null || evidence.aiJobAttemptId() == null || evidence.attempt() == null
				|| evidence.inputSha256() == null || evidence.resultSha256() == null) {
			throw invalid("The successful MediaPipe provenance is incomplete.");
		}
		output.putAttribute("ai_job_id", evidence.aiJobId().toString());
		output.putAttribute("ai_job_attempt_id", evidence.aiJobAttemptId().toString());
		output.putAttribute("attempt", evidence.attempt());
		output.putAttribute("algorithm_key", evidence.algorithmKey());
		output.putAttribute("algorithm_version", evidence.algorithmVersion());
		output.putAttribute("model_sha256", evidence.modelSha256());
		output.putAttribute("profile_sha256", evidence.profileSha256());
		output.putAttribute("input_sha256", evidence.inputSha256());
		output.putAttribute("result_sha256", evidence.resultSha256());
		var candidates = evidence.candidates();
		if (candidates.isEmpty()) {
			throw invalid("A successful MediaPipe hand result has no candidate samples.");
		}
		if (candidates.stream()
			.anyMatch(candidate -> !projectId.equals(candidate.projectId())
					|| !view.clipAssetId().equals(candidate.clipAssetId())
					|| !evidence.aiJobId().equals(candidate.aiJobId()))) {
			throw invalid("A MediaPipe candidate does not match the current source video.");
		}
		var ordered = candidates.stream()
			.sorted(Comparator.comparingLong(CandidateAnnotationSnapshot::frameTimestampMs)
				.thenComparingInt(CandidateAnnotationSnapshot::handIndex)
				.thenComparing(candidate -> candidate.candidateAnnotationId().toString()))
			.toList();
		var landmarks = new float[ordered.size()][21][3];
		for (var index = 0; index < ordered.size(); index += 1) {
			copyLandmarks(ordered.get(index), landmarks[index]);
		}
		var candidateGroup = output.putGroup("candidates");
		candidateGroup.putDataset("candidate_annotation_id",
				ordered.stream().map(candidate -> candidate.candidateAnnotationId().toString()).toArray(String[]::new));
		candidateGroup.putDataset("frame_timestamp_ms",
				ordered.stream().mapToLong(CandidateAnnotationSnapshot::frameTimestampMs).toArray());
		candidateGroup.putDataset("hand_index",
				ordered.stream().mapToInt(CandidateAnnotationSnapshot::handIndex).toArray());
		candidateGroup.putDataset("handedness",
				ordered.stream().map(CandidateAnnotationSnapshot::handedness).toArray(String[]::new));
		candidateGroup.putDataset("handedness_score",
				ordered.stream().mapToDouble(candidate -> candidate.handednessScore().doubleValue()).toArray());
		candidateGroup.putDataset("image_width",
				ordered.stream().mapToInt(CandidateAnnotationSnapshot::imageWidth).toArray());
		candidateGroup.putDataset("image_height",
				ordered.stream().mapToInt(CandidateAnnotationSnapshot::imageHeight).toArray());
		candidateGroup.putDataset("landmarks", landmarks);
	}

	private static void copyLandmarks(CandidateAnnotationSnapshot candidate, float[][] target) {
		var landmarks = candidate.landmarks();
		if (landmarks == null || !landmarks.isArray() || landmarks.size() != 21) {
			throw invalid("A MediaPipe candidate does not contain 21 landmarks.");
		}
		for (var index = 0; index < 21; index += 1) {
			var point = landmarks.get(index);
			target[index][0] = finiteCoordinate(point, "x");
			target[index][1] = finiteCoordinate(point, "y");
			target[index][2] = finiteCoordinate(point, "z");
		}
	}

	private static float finiteCoordinate(JsonNode point, String coordinate) {
		if (point == null || !point.isObject() || !point.has(coordinate) || !point.get(coordinate).isNumber()) {
			throw invalid("A MediaPipe landmark coordinate is invalid.");
		}
		var value = point.get(coordinate).floatValue();
		if (!Float.isFinite(value)) {
			throw invalid("A MediaPipe landmark coordinate is not finite.");
		}
		return value;
	}

	private static TrainingDataExportException unavailable(String detail) {
		return new TrainingDataExportException("HDF5_EXPORT_NOT_READY", detail, 422);
	}

	private static TrainingDataExportException invalid(String detail) {
		return new TrainingDataExportException("HDF5_EXPORT_DATA_INVALID", detail, 422);
	}

	private void requireSyntheticProfile() {
		if (!"synthetic".equals(this.dataProfile)) {
			throw new TrainingDataExportException("HDF5_EXPORT_UNAVAILABLE",
					"HDF5 demonstration export is enabled only for the synthetic profile until a non-synthetic authorization model is defined.",
					403);
		}
	}

}
