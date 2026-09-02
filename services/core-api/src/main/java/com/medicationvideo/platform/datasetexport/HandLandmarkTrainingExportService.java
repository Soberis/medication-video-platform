package com.medicationvideo.platform.datasetexport;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateAnnotationSnapshot;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;

import tools.jackson.databind.JsonNode;

/**
 * Exports the current MediaPipe hand-landmark detection facts for every enabled,
 * available CaptureGroup camera stream. This export is intentionally independent from the
 * human quality, annotation, review and cleaning workflows.
 */
@Service
class HandLandmarkTrainingExportService {

	static final String SCHEMA_VERSION = "hand-landmarks.v1";

	static final String MEDIA_TYPE = "text/csv; charset=UTF-8";

	static final String FILE_NAME = "mediapipe_hand_landmarks_v1.csv";

	static final List<String> CSV_COLUMNS = csvColumns();

	private static final String SHA256_PATTERN = "^[a-f0-9]{64}$";

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CandidateEvidenceQueryService candidateEvidenceQueryService;

	private final String dataProfile;

	HandLandmarkTrainingExportService(CaptureGroupViewQueryService captureGroupViewQueryService,
			CandidateEvidenceQueryService candidateEvidenceQueryService,
			@Value("${platform.training-data-export.data-profile:disabled}") String dataProfile) {
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.candidateEvidenceQueryService = candidateEvidenceQueryService;
		this.dataProfile = dataProfile == null ? "" : dataProfile.trim().toLowerCase(Locale.ROOT);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	HandLandmarkTrainingExportPreviewResponse preview(UUID projectId) {
		var snapshot = snapshot(projectId);
		return new HandLandmarkTrainingExportPreviewResponse(SCHEMA_VERSION, snapshot.handDetectionViewCount(),
				snapshot.completedViewCount(), snapshot.viewsWithHand(), snapshot.rows().size(),
				snapshot.noJobViewCount(), snapshot.queuedViewCount(), snapshot.runningViewCount(),
				snapshot.failedViewCount(), snapshot.noHandViewCount(), FILE_NAME, MEDIA_TYPE);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	byte[] download(UUID projectId) {
		return toCsv(snapshot(projectId).rows());
	}

	private ExportSnapshot snapshot(UUID projectId) {
		requireSyntheticProfile();
		var views = readDetectionViews(projectId);
		if (views.isEmpty()) {
			return new ExportSnapshot(0, 0, 0, 0, 0, 0, 0, 0, List.of());
		}

		var evidenceByAsset = new java.util.HashMap<UUID, CandidateEvidence>();
		for (var evidence : this.candidateEvidenceQueryService.findByClipAssets(projectId,
				views.stream().map(DetectionView::clipAssetId).distinct().toList())) {
			if (evidence == null || evidence.clipAssetId() == null
					|| evidenceByAsset.put(evidence.clipAssetId(), evidence) != null) {
				throw provenanceInvalid("The current MediaPipe evidence contains duplicate ClipAsset entries.");
			}
		}

		var rows = new ArrayList<DetectionRow>();
		long completedViewCount = 0;
		long viewsWithHand = 0;
		long noJobViewCount = 0;
		long queuedViewCount = 0;
		long runningViewCount = 0;
		long failedViewCount = 0;
		long noHandViewCount = 0;
		for (var view : views) {
			var evidence = evidenceByAsset.get(view.clipAssetId());
			validateEvidenceBinding(view, evidence);
			switch (evidence.executionStatus()) {
				case CandidateEvidenceQueryService.EXECUTION_STATUS_NO_JOB -> {
					validateEmptyState(evidence);
					noJobViewCount += 1;
				}
				case CandidateEvidenceQueryService.EXECUTION_STATUS_QUEUED -> {
					validateEmptyState(evidence);
					queuedViewCount += 1;
				}
				case CandidateEvidenceQueryService.EXECUTION_STATUS_RUNNING -> {
					validateEmptyState(evidence);
					runningViewCount += 1;
				}
				case CandidateEvidenceQueryService.EXECUTION_STATUS_FAILED -> {
					validateEmptyState(evidence);
					completedViewCount += 1;
					failedViewCount += 1;
				}
				case CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_NO_HAND -> {
					validateEmptyState(evidence);
					completedViewCount += 1;
					noHandViewCount += 1;
				}
				case CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES -> {
					validateSuccessfulEvidence(view, evidence);
					completedViewCount += 1;
					viewsWithHand += 1;
					for (var candidate : evidence.candidates()) {
						validateCandidate(candidate, projectId, view, evidence.aiJobId());
						rows.add(new DetectionRow(view.captureGroupId(), view.cameraStreamId(), view.cameraNumber(),
								view.clipAssetId(), view.clipAssetSha256(), candidate, evidence));
					}
				}
				default -> throw provenanceInvalid("The current MediaPipe execution status is unsupported.");
			}
		}

		rows.sort(Comparator.comparing((DetectionRow row) -> row.captureGroupId().toString())
			.thenComparingInt(DetectionRow::cameraNumber)
			.thenComparing(row -> row.cameraStreamId().toString())
			.thenComparing(row -> row.clipAssetId().toString())
			.thenComparingLong(row -> row.candidate().frameTimestampMs())
			.thenComparingInt(row -> row.candidate().handIndex())
			.thenComparing(row -> row.candidate().candidateAnnotationId().toString()));
		return new ExportSnapshot(views.size(), completedViewCount, viewsWithHand, noJobViewCount, queuedViewCount,
				runningViewCount, failedViewCount, noHandViewCount, List.copyOf(rows));
	}

	private List<DetectionView> readDetectionViews(UUID projectId) {
		var views = new ArrayList<DetectionView>();
		for (var group : this.captureGroupViewQueryService.findAll(projectId)) {
			validateGroup(group, projectId);
			for (var view : group.views()) {
				if (view == null || !view.enabledForHandDetection() || !view.present()) {
					continue;
				}
				validateDetectionView(view);
				views.add(new DetectionView(group.captureGroupId(), view.cameraStreamId(), view.cameraNumber(),
						view.clipAssetId(), view.clipAssetSha256()));
			}
		}
		views.sort(Comparator.comparing((DetectionView view) -> view.captureGroupId().toString())
			.thenComparingInt(DetectionView::cameraNumber)
			.thenComparing(view -> view.cameraStreamId().toString())
			.thenComparing(view -> view.clipAssetId().toString()));
		return List.copyOf(views);
	}

	private static void validateGroup(CaptureGroupFacts group, UUID projectId) {
		if (group == null || !projectId.equals(group.projectId()) || group.captureGroupId() == null
				|| group.views() == null) {
			throw provenanceInvalid("The current CaptureGroup view projection is incomplete.");
		}
	}

	private static void validateDetectionView(CaptureGroupView view) {
		if (view.cameraStreamId() == null || view.clipAssetId() == null || view.cameraNumber() < 1
				|| !view.assetAvailable() || "UNAVAILABLE".equals(view.mediaStatus())
				|| !validSha256(view.clipAssetSha256())) {
			throw provenanceInvalid("A current hand-detection camera stream is unavailable or incomplete.");
		}
	}

	private static void validateEvidenceBinding(DetectionView view, CandidateEvidence evidence) {
		if (evidence == null || !evidence.provenanceValid() || !view.clipAssetId().equals(evidence.clipAssetId())
				|| !Objects.equals(view.clipAssetSha256(), evidence.clipAssetSha256())) {
			throw provenanceInvalid("The current MediaPipe evidence does not match the current ClipAsset.");
		}
		if (evidence.executionStatus() == null) {
			throw provenanceInvalid("The current MediaPipe execution status is missing.");
		}
	}

	private static void validateEmptyState(CandidateEvidence evidence) {
		if (evidence.candidateCount() != 0 || evidence.candidates() == null || !evidence.candidates().isEmpty()) {
			throw provenanceInvalid("A MediaPipe execution without hand candidates contains candidate rows.");
		}
	}

	private static void validateSuccessfulEvidence(DetectionView view, CandidateEvidence evidence) {
		if (evidence.candidates() == null || evidence.candidates().isEmpty()
				|| evidence.candidateCount() != evidence.candidates().size() || evidence.candidateSetDigest() == null
				|| !evidence.candidateSetDigest()
					.equals(CandidateEvidenceQueryService.candidateSetDigest(evidence.candidates()))
				|| !view.clipAssetSha256().equals(evidence.inputSha256()) || evidence.aiJobId() == null
				|| evidence.aiJobAttemptId() == null || evidence.attempt() == null || evidence.attempt() < 1
				|| evidence.messageId() == null || !"MEDIAPIPE_HAND_LANDMARKER".equals(evidence.algorithmKey())
				|| evidence.algorithmVersion() == null || evidence.algorithmVersion().isBlank()
				|| !validSha256(evidence.modelSha256()) || !validSha256(evidence.profileSha256())
				|| !validSha256(evidence.inputSha256()) || !validSha256(evidence.resultSha256())
				|| !"HAND_DETECTED".equals(evidence.handResult()) || evidence.runSummary() == null) {
			throw provenanceInvalid("The current MediaPipe success provenance is incomplete or mismatched.");
		}
	}

	private static void validateCandidate(CandidateAnnotationSnapshot candidate, UUID projectId, DetectionView view,
			UUID aiJobId) {
		if (candidate == null || candidate.candidateAnnotationId() == null || aiJobId == null
				|| !aiJobId.equals(candidate.aiJobId()) || !projectId.equals(candidate.projectId())
				|| !view.clipAssetId().equals(candidate.clipAssetId()) || candidate.frameTimestampMs() < 0
				|| candidate.handIndex() < 0 || candidate.handIndex() > 1 || candidate.imageWidth() <= 0
				|| candidate.imageHeight() <= 0 || candidate.handedness() == null
				|| !List.of("LEFT", "RIGHT", "UNKNOWN").contains(candidate.handedness())
				|| candidate.handednessScore() == null || !Double.isFinite(candidate.handednessScore().doubleValue())
				|| candidate.handednessScore().signum() < 0 || candidate.handednessScore().compareTo(BigDecimal.ONE) > 0
				|| candidate.validLandmarkCount() != 21 || candidate.landmarks() == null
				|| !candidate.landmarks().isArray() || candidate.landmarks().size() != 21) {
			throw candidateInvalid("A CandidateAnnotation does not satisfy the 21-point MediaPipe contract.");
		}
		validateBoundingBox(candidate.boundingBox());
		for (var point : candidate.landmarks()) {
			if (point == null || !point.isObject()) {
				throw candidateInvalid("A MediaPipe landmark must be an object.");
			}
			finiteNumber(point, "x");
			finiteNumber(point, "y");
			finiteNumber(point, "z");
		}
	}

	private static void validateBoundingBox(JsonNode boundingBox) {
		if (boundingBox == null || !boundingBox.isObject()) {
			throw candidateInvalid("The CandidateAnnotation bounding box is not an object.");
		}
		var width = finiteNumber(boundingBox, "width");
		var height = finiteNumber(boundingBox, "height");
		finiteNumber(boundingBox, "x");
		finiteNumber(boundingBox, "y");
		if (width.signum() <= 0 || height.signum() <= 0) {
			throw candidateInvalid("The CandidateAnnotation bounding box must have positive dimensions.");
		}
	}

	private static BigDecimal finiteNumber(JsonNode object, String field) {
		var value = object.get(field);
		if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
			throw candidateInvalid("The CandidateAnnotation " + field + " coordinate must be finite.");
		}
		try {
			return new BigDecimal(value.asText());
		}
		catch (NumberFormatException exception) {
			throw candidateInvalid("The CandidateAnnotation " + field + " coordinate is not numeric.");
		}
	}

	private static byte[] toCsv(List<DetectionRow> rows) {
		var csv = new StringBuilder();
		appendRecord(csv, CSV_COLUMNS);
		for (var row : rows) {
			var candidate = row.candidate();
			var evidence = row.evidence();
			var values = new ArrayList<String>(CSV_COLUMNS.size());
			values.add(row.captureGroupId().toString());
			values.add(row.cameraStreamId().toString());
			values.add(Integer.toString(row.cameraNumber()));
			values.add(row.clipAssetId().toString());
			values.add(row.clipAssetSha256());
			values.add(candidate.candidateAnnotationId().toString());
			values.add(Long.toString(candidate.frameTimestampMs()));
			values.add(Integer.toString(candidate.handIndex()));
			values.add(candidate.handedness());
			values.add(decimal(candidate.handednessScore()));
			values.add(Integer.toString(candidate.imageWidth()));
			values.add(Integer.toString(candidate.imageHeight()));
			values.add(Integer.toString(candidate.validLandmarkCount()));
			values.add(decimalValue(candidate.boundingBox(), "x"));
			values.add(decimalValue(candidate.boundingBox(), "y"));
			values.add(decimalValue(candidate.boundingBox(), "width"));
			values.add(decimalValue(candidate.boundingBox(), "height"));
			for (var index = 0; index < candidate.landmarks().size(); index += 1) {
				var point = candidate.landmarks().get(index);
				values.add(decimalValue(point, "x"));
				values.add(decimalValue(point, "y"));
				values.add(decimalValue(point, "z"));
			}
			values.add(evidence.aiJobId().toString());
			values.add(evidence.aiJobAttemptId().toString());
			values.add(Integer.toString(evidence.attempt()));
			values.add(evidence.algorithmKey());
			values.add(evidence.algorithmVersion());
			values.add(evidence.modelSha256());
			values.add(evidence.profileSha256());
			values.add(evidence.inputSha256());
			values.add(evidence.resultSha256());
			appendRecord(csv, values);
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static List<String> csvColumns() {
		var columns = new ArrayList<>(List.of("capture_group_id", "camera_stream_id", "camera_number", "clip_asset_id",
				"clip_asset_sha256", "candidate_annotation_id", "frame_timestamp_ms", "hand_index", "handedness",
				"handedness_score", "image_width", "image_height", "valid_landmark_count", "bounding_box_x",
				"bounding_box_y", "bounding_box_width", "bounding_box_height"));
		for (var index = 0; index < 21; index += 1) {
			var prefix = "landmark_%02d_".formatted(index);
			columns.add(prefix + "x");
			columns.add(prefix + "y");
			columns.add(prefix + "z");
		}
		columns.addAll(List.of("ai_job_id", "ai_job_attempt_id", "attempt", "algorithm_key", "algorithm_version",
				"model_sha256", "profile_sha256", "input_sha256", "result_sha256"));
		return List.copyOf(columns);
	}

	private static void appendRecord(StringBuilder csv, List<String> values) {
		for (var index = 0; index < values.size(); index += 1) {
			if (index > 0) {
				csv.append(',');
			}
			appendCell(csv, values.get(index));
		}
		csv.append('\n');
	}

	private static void appendCell(StringBuilder csv, String value) {
		var needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
				|| value.indexOf('\r') >= 0;
		if (!needsQuotes) {
			csv.append(value);
			return;
		}
		csv.append('"');
		for (var index = 0; index < value.length(); index += 1) {
			var character = value.charAt(index);
			if (character == '"') {
				csv.append('"');
			}
			csv.append(character);
		}
		csv.append('"');
	}

	private static String decimal(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private static String decimalValue(JsonNode object, String field) {
		return decimal(finiteNumber(object, field));
	}

	private static boolean validSha256(String value) {
		return value != null && value.matches(SHA256_PATTERN);
	}

	private void requireSyntheticProfile() {
		if (!"synthetic".equals(this.dataProfile)) {
			throw new TrainingDataExportException("HAND_LANDMARK_EXPORT_UNAVAILABLE",
					"MediaPipe hand-landmark export is enabled only for the synthetic profile until a non-synthetic authorization model is defined.",
					403);
		}
	}

	private static TrainingDataExportException provenanceInvalid(String detail) {
		return new TrainingDataExportException("HAND_LANDMARK_EXPORT_EVIDENCE_INVALID",
				"The current MediaPipe evidence is incomplete or mismatched (" + detail + "); export is blocked.", 422);
	}

	private static TrainingDataExportException candidateInvalid(String detail) {
		return new TrainingDataExportException("HAND_LANDMARK_EXPORT_DATA_INVALID",
				"The selected MediaPipe CandidateAnnotation is invalid (" + detail + "); export is blocked.", 422);
	}

	private record DetectionView(UUID captureGroupId, UUID cameraStreamId, int cameraNumber, UUID clipAssetId,
			String clipAssetSha256) {
	}

	private record DetectionRow(UUID captureGroupId, UUID cameraStreamId, int cameraNumber, UUID clipAssetId,
			String clipAssetSha256, CandidateAnnotationSnapshot candidate, CandidateEvidence evidence) {
	}

	private record ExportSnapshot(long handDetectionViewCount, long completedViewCount, long viewsWithHand,
			long noJobViewCount, long queuedViewCount, long runningViewCount, long failedViewCount,
			long noHandViewCount, List<DetectionRow> rows) {
	}

}
