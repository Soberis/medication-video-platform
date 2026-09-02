package com.medicationvideo.platform.datasetexport;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateAnnotationSnapshot;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.FrozenExecutionSnapshot;

import tools.jackson.databind.JsonNode;

/**
 * Exports MediaPipe hand landmarks joined to the human-approved Primary View action
 * intervals. The event gate remains owned by the V1.27 training-data projection; this
 * service only materializes frame/hand rows from the frozen AI evidence identified by
 * that projection.
 */
@Service
class HandLandmarkTrainingExportService {

	static final String SCHEMA_VERSION = "hand-landmarks.v1";

	static final String MEDIA_TYPE = "text/csv; charset=UTF-8";

	static final String FILE_NAME = "mediapipe_hand_landmarks_v1.csv";

	private static final String REASON_MEDIAPIPE_NOT_FINISHED = "MEDIAPIPE_NOT_FINISHED";

	private static final String REASON_NO_HAND_DETECTED = "NO_HAND_DETECTED";

	private static final String REASON_MEDIAPIPE_FAILED = "MEDIAPIPE_FAILED";

	private static final String REASON_NO_VALID_21_POINT_SAMPLE = "NO_VALID_21_POINT_SAMPLE";

	private static final List<String> REASON_KEYS = List.of(REASON_MEDIAPIPE_NOT_FINISHED, REASON_NO_HAND_DETECTED,
			REASON_MEDIAPIPE_FAILED, REASON_NO_VALID_21_POINT_SAMPLE);

	static final List<String> CSV_COLUMNS = csvColumns();

	private final TrainingDataV3ExportService eventEligibilityService;

	private final CandidateEvidenceQueryService candidateEvidenceQueryService;

	HandLandmarkTrainingExportService(TrainingDataV3ExportService eventEligibilityService,
			CandidateEvidenceQueryService candidateEvidenceQueryService) {
		this.eventEligibilityService = eventEligibilityService;
		this.candidateEvidenceQueryService = candidateEvidenceQueryService;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	HandLandmarkTrainingExportPreviewResponse preview(UUID projectId) {
		var snapshot = snapshot(projectId);
		return new HandLandmarkTrainingExportPreviewResponse(SCHEMA_VERSION, snapshot.eligibleEventCount(),
				snapshot.eventsWithLandmarks(), snapshot.rows().size(),
				snapshot.eligibleEventCount() - snapshot.eventsWithLandmarks(), FILE_NAME, MEDIA_TYPE,
				snapshot.reasonCounts());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	byte[] download(UUID projectId) {
		return toCsv(snapshot(projectId).rows());
	}

	private ExportSnapshot snapshot(UUID projectId) {
		var events = readEligibleEvents(projectId);
		var rows = new ArrayList<TrainingRow>();
		var reasonCounts = initialReasonCounts();
		long eventsWithLandmarks = 0;
		for (var event : events) {
			validateEventIdentity(event);
			var primaryEvidence = primaryEvidence(event);
			var frozenClipAssetSha256 = frozenPrimaryClipAssetSha256(event);
			if (!frozenClipAssetSha256.equals(primaryEvidence.clipAssetSha256())) {
				throw provenanceInvalid(
						"The approved AI evidence does not match the frozen Primary View ClipAsset SHA-256.");
			}
			var annotationDocument = this.eventEligibilityService.readApprovedAnnotationDocument(projectId,
					event.captureGroupId(), event.annotationRevisionId());
			var intervals = actionIntervals(annotationDocument);
			if (!CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES
				.equals(primaryEvidence.executionStatus())) {
				incrementReason(reasonCounts, reasonFor(primaryEvidence.executionStatus()));
				continue;
			}

			var execution = this.candidateEvidenceQueryService.findFrozenExecution(primaryEvidence.aiJobId(),
					primaryEvidence.aiJobAttemptId(), primaryEvidence.attempt(), primaryEvidence.messageId());
			validateExecution(execution, projectId, event.primaryClipAssetId(), primaryEvidence, frozenClipAssetSha256);
			var candidates = execution.candidates();
			for (var candidate : candidates) {
				validateCandidate(candidate, projectId, event.primaryClipAssetId(), primaryEvidence.aiJobId());
			}
			if (candidates.size() != primaryEvidence.candidateCount()) {
				throw provenanceInvalid("The live CandidateAnnotation count does not match the approved AI evidence.");
			}
			var candidateSetDigest = CandidateEvidenceQueryService.candidateSetDigest(candidates);
			if (!candidateSetDigest.equals(primaryEvidence.candidateSetDigest())) {
				throw provenanceInvalid(
						"The live CandidateAnnotation set does not match the approved AI evidence digest.");
			}

			var eventRows = new ArrayList<TrainingRow>();
			for (var candidate : candidates) {
				var interval = matchingInterval(intervals, candidate.frameTimestampMs());
				if (interval != null) {
					eventRows.add(new TrainingRow(event.captureGroupId(), event.annotationRevisionId(),
							event.primaryClipAssetId(), frozenClipAssetSha256, event.primaryCameraNumber(), candidate,
							primaryEvidence, interval));
				}
			}
			if (eventRows.isEmpty()) {
				incrementReason(reasonCounts, REASON_NO_VALID_21_POINT_SAMPLE);
			}
			else {
				eventsWithLandmarks += 1;
				rows.addAll(eventRows);
			}
		}
		rows.sort(Comparator.comparing((TrainingRow row) -> row.captureGroupId().toString())
			.thenComparingLong(row -> row.candidate().frameTimestampMs())
			.thenComparingInt(row -> row.candidate().handIndex())
			.thenComparing(row -> row.annotationRevisionId().toString())
			.thenComparing(row -> row.candidate().candidateAnnotationId().toString()));
		return new ExportSnapshot(events.size(), eventsWithLandmarks, List.copyOf(rows), reasonCounts);
	}

	private List<TrainingDataV3ExportService.TrainingDataV3Event> readEligibleEvents(UUID projectId) {
		try {
			return this.eventEligibilityService.readEligibleDocument(projectId).events();
		}
		catch (TrainingDataExportException exception) {
			if ("TRAINING_DATA_EXPORT_UNAVAILABLE".equals(exception.getErrorCode())) {
				throw new TrainingDataExportException("HAND_LANDMARK_EXPORT_UNAVAILABLE",
						"Hand landmark training-data export is not enabled for the current data profile.", 403);
			}
			if ("TRAINING_DATA_V3_EVIDENCE_INVALID".equals(exception.getErrorCode())
					|| "TRAINING_DATA_V3_CANDIDATE_PROVENANCE_INVALID".equals(exception.getErrorCode())) {
				throw provenanceInvalid("The current V1.27 evidence projection is invalid.");
			}
			throw exception;
		}
	}

	private static void validateEventIdentity(TrainingDataV3ExportService.TrainingDataV3Event event) {
		if (event == null || event.captureGroupId() == null || event.annotationRevisionId() == null
				|| event.primaryClipAssetId() == null || event.primaryCameraNumber() < 1) {
			throw provenanceInvalid("The eligible event identity is incomplete.");
		}
	}

	private PrimaryEvidence primaryEvidence(TrainingDataV3ExportService.TrainingDataV3Event event) {
		TrainingDataV3ExportService.validateCandidateProvenance(event.candidateEvidence(), "candidate_evidence");
		var matching = new ArrayList<JsonNode>();
		for (var entry : event.candidateEvidence()) {
			var clipAssetId = entry.get("clip_asset_id");
			if (clipAssetId != null && event.primaryClipAssetId().toString().equals(clipAssetId.asText())) {
				matching.add(entry);
			}
		}
		if (matching.size() != 1) {
			throw provenanceInvalid("The approved event must contain exactly one Primary View AI evidence entry.");
		}
		var entry = matching.getFirst();
		if (!requiredBoolean(entry, "provenance_valid")
				|| !event.primaryClipAssetId().toString().equals(requiredText(entry, "clip_asset_id"))) {
			throw provenanceInvalid("The Primary View AI evidence is not provenance-valid.");
		}
		var executionStatus = requiredText(entry, "execution_status");
		var candidateCount = requiredNonNegativeLong(entry, "candidate_count");
		if (!CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES.equals(executionStatus)) {
			return new PrimaryEvidence(requiredSha(entry, "clip_asset_sha256"), executionStatus, null, null, null, null,
					null, null, null, null, null, null, candidateCount, null, null);
		}
		return new PrimaryEvidence(requiredSha(entry, "clip_asset_sha256"), executionStatus,
				requiredUuid(entry, "ai_job_id"), requiredUuid(entry, "ai_job_attempt_id"),
				requiredInt(entry, "attempt"), requiredUuid(entry, "message_id"), requiredSha(entry, "input_sha256"),
				requiredText(entry, "algorithm_key"), requiredText(entry, "algorithm_version"),
				requiredSha(entry, "model_sha256"), requiredSha(entry, "profile_sha256"),
				requiredSha(entry, "result_sha256"), candidateCount, requiredSha(entry, "candidate_set_digest"),
				requiredRunSummary(entry.get("run_summary")));
	}

	private String frozenPrimaryClipAssetSha256(TrainingDataV3ExportService.TrainingDataV3Event event) {
		var matching = new ArrayList<JsonNode>();
		if (event.participatingViews() == null || !event.participatingViews().isArray()) {
			throw provenanceInvalid("The eligible event has no participating Primary View evidence.");
		}
		for (var view : event.participatingViews()) {
			var clipAssetId = view.isObject() ? view.get("clip_asset_id") : null;
			if (view.isObject() && clipAssetId != null
					&& event.primaryClipAssetId().toString().equals(clipAssetId.asText()) && view.has("camera_number")
					&& view.get("camera_number").isIntegralNumber()
					&& view.get("camera_number").intValue() == event.primaryCameraNumber()) {
				matching.add(view);
			}
		}
		if (matching.size() != 1) {
			throw provenanceInvalid("The eligible event must contain exactly one frozen Primary View.");
		}
		var primary = matching.getFirst();
		if (!"PRIMARY_AUTHORITATIVE".equals(requiredText(primary, "interval_source"))
				|| !requiredBoolean(primary, "action_aligned_interval")) {
			throw provenanceInvalid("The Primary View is not the authoritative action timebase.");
		}
		return requiredSha(primary, "clip_asset_sha256");
	}

	private List<ActionInterval> actionIntervals(JsonNode annotationDocument) {
		var segments = annotationDocument == null ? null : annotationDocument.get("segments");
		if (segments == null || !segments.isArray() || segments.isEmpty()) {
			throw provenanceInvalid("The approved AnnotationRevision action interval is missing.");
		}
		var intervals = new ArrayList<ActionInterval>();
		for (var interval : segments) {
			if (interval == null || !interval.isObject()) {
				throw provenanceInvalid("The approved AnnotationRevision action interval is malformed.");
			}
			var start = requiredNonNegativeLong(interval, "start_ms");
			var end = requiredNonNegativeLong(interval, "end_ms");
			var label = requiredText(interval, "label");
			if (end <= start) {
				throw provenanceInvalid("The approved AnnotationRevision action interval is invalid.");
			}
			intervals.add(new ActionInterval(start, end, label));
		}
		intervals.sort(Comparator.comparingLong(ActionInterval::startMs).thenComparingLong(ActionInterval::endMs));
		var previousEnd = -1L;
		for (var interval : intervals) {
			if (interval.startMs() < previousEnd) {
				throw provenanceInvalid("The approved AnnotationRevision action intervals overlap.");
			}
			previousEnd = interval.endMs();
		}
		return List.copyOf(intervals);
	}

	private static ActionInterval matchingInterval(List<ActionInterval> intervals, long timestampMs) {
		for (var interval : intervals) {
			if (interval.startMs() <= timestampMs && timestampMs < interval.endMs()) {
				return interval;
			}
		}
		return null;
	}

	private void validateExecution(FrozenExecutionSnapshot execution, UUID projectId, UUID primaryClipAssetId,
			PrimaryEvidence evidence, String frozenClipAssetSha256) {
		if (execution == null) {
			throw provenanceInvalid("The approved AI job attempt no longer exists.");
		}
		if (!evidence.aiJobId().equals(execution.aiJobId()) || !projectId.equals(execution.projectId())
				|| !primaryClipAssetId.equals(execution.clipAssetId())
				|| !evidence.aiJobAttemptId().equals(execution.aiJobAttemptId())
				|| evidence.attempt() != execution.attempt() || !evidence.messageId().equals(execution.messageId())
				|| !frozenClipAssetSha256.equals(evidence.inputSha256())
				|| !evidence.inputSha256().equals(execution.inputSha256())
				|| !evidence.algorithmKey().equals(execution.algorithmKey())
				|| !evidence.algorithmVersion().equals(execution.algorithmVersion())
				|| !evidence.modelSha256().equals(execution.modelSha256())
				|| !evidence.profileSha256().equals(execution.profileSha256())
				|| !Objects.equals(evidence.runSummary(), execution.runSummary())
				|| !"SUCCEEDED".equals(execution.status()) || !"HAND_DETECTED".equals(execution.handResult())
				|| !"SUCCEEDED".equals(execution.attemptStatus()) || execution.resultSha256() == null
				|| !evidence.resultSha256().equals(execution.resultSha256())) {
			throw provenanceInvalid("The live AI job or attempt no longer matches the approved evidence.");
		}
	}

	private static void validateCandidate(CandidateAnnotationSnapshot candidate, UUID projectId,
			UUID primaryClipAssetId, UUID aiJobId) {
		if (candidate == null || candidate.candidateAnnotationId() == null || !aiJobId.equals(candidate.aiJobId())
				|| !projectId.equals(candidate.projectId()) || !primaryClipAssetId.equals(candidate.clipAssetId())
				|| candidate.frameTimestampMs() < 0 || candidate.handIndex() < 0 || candidate.handIndex() > 1
				|| candidate.imageWidth() <= 0 || candidate.imageHeight() <= 0 || candidate.handedness() == null
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

	private static byte[] toCsv(List<TrainingRow> rows) {
		var csv = new StringBuilder();
		appendRecord(csv, CSV_COLUMNS);
		for (var row : rows) {
			var candidate = row.candidate();
			var values = new ArrayList<String>(CSV_COLUMNS.size());
			values.add(row.captureGroupId().toString());
			values.add(row.annotationRevisionId().toString());
			values.add(row.clipAssetId().toString());
			values.add(row.clipAssetSha256());
			values.add(Integer.toString(row.cameraNumber()));
			values.add(Long.toString(candidate.frameTimestampMs()));
			values.add(Integer.toString(candidate.handIndex()));
			values.add(candidate.handedness());
			values.add(decimal(candidate.handednessScore()));
			values.add(Integer.toString(candidate.imageWidth()));
			values.add(Integer.toString(candidate.imageHeight()));
			values.add(Integer.toString(candidate.validLandmarkCount()));
			values.add(row.interval().label());
			values.add(Long.toString(row.interval().startMs()));
			values.add(Long.toString(row.interval().endMs()));
			values.add(row.evidence().aiJobId().toString());
			values.add(row.evidence().aiJobAttemptId().toString());
			values.add(row.evidence().algorithmKey());
			values.add(row.evidence().algorithmVersion());
			values.add(row.evidence().modelSha256());
			values.add(row.evidence().profileSha256());
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
			appendRecord(csv, values);
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static List<String> csvColumns() {
		var columns = new ArrayList<>(List.of("capture_group_id", "annotation_revision_id", "clip_asset_id",
				"clip_asset_sha256", "camera_number", "frame_timestamp_ms", "hand_index", "handedness",
				"handedness_score", "image_width", "image_height", "valid_landmark_count", "action_label",
				"segment_start_ms", "segment_end_ms", "ai_job_id", "ai_job_attempt_id", "algorithm_key",
				"algorithm_version", "model_sha256", "profile_sha256", "bounding_box_x", "bounding_box_y",
				"bounding_box_width", "bounding_box_height"));
		for (var index = 0; index < 21; index += 1) {
			var prefix = "landmark_%02d_".formatted(index);
			columns.add(prefix + "x");
			columns.add(prefix + "y");
			columns.add(prefix + "z");
		}
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

	private static String reasonFor(String executionStatus) {
		return switch (executionStatus) {
			case CandidateEvidenceQueryService.EXECUTION_STATUS_NO_JOB,
					CandidateEvidenceQueryService.EXECUTION_STATUS_QUEUED,
					CandidateEvidenceQueryService.EXECUTION_STATUS_RUNNING ->
				REASON_MEDIAPIPE_NOT_FINISHED;
			case CandidateEvidenceQueryService.EXECUTION_STATUS_FAILED -> REASON_MEDIAPIPE_FAILED;
			case CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_NO_HAND -> REASON_NO_HAND_DETECTED;
			default -> throw provenanceInvalid("The Primary View MediaPipe execution status is unsupported.");
		};
	}

	private static Map<String, Long> initialReasonCounts() {
		var counts = new LinkedHashMap<String, Long>();
		for (var key : REASON_KEYS) {
			counts.put(key, 0L);
		}
		return counts;
	}

	private static void incrementReason(Map<String, Long> counts, String reason) {
		counts.computeIfPresent(reason, (ignored, value) -> value + 1);
	}

	private static String requiredText(JsonNode object, String field) {
		var value = object.get(field);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw provenanceInvalid("The approved evidence is missing " + field + ".");
		}
		return value.asText();
	}

	private static String requiredSha(JsonNode object, String field) {
		var value = requiredText(object, field);
		if (!value.matches("^[a-f0-9]{64}$")) {
			throw provenanceInvalid("The approved evidence contains an invalid " + field + ".");
		}
		return value;
	}

	private static UUID requiredUuid(JsonNode object, String field) {
		try {
			return UUID.fromString(requiredText(object, field));
		}
		catch (IllegalArgumentException exception) {
			throw provenanceInvalid("The approved evidence contains an invalid " + field + ".");
		}
	}

	private static int requiredInt(JsonNode object, String field) {
		var value = object.get(field);
		if (value == null || !value.isIntegralNumber()) {
			throw provenanceInvalid("The approved evidence contains an invalid " + field + ".");
		}
		try {
			var parsed = Integer.parseInt(value.asText());
			if (parsed < 1) {
				throw new NumberFormatException();
			}
			return parsed;
		}
		catch (NumberFormatException exception) {
			throw provenanceInvalid("The approved evidence contains an invalid " + field + ".");
		}
	}

	private static long requiredNonNegativeLong(JsonNode object, String field) {
		var value = object.get(field);
		if (value == null || !value.isIntegralNumber()) {
			throw provenanceInvalid("The approved evidence contains an invalid " + field + ".");
		}
		try {
			var parsed = Long.parseLong(value.asText());
			if (parsed < 0) {
				throw new NumberFormatException();
			}
			return parsed;
		}
		catch (NumberFormatException exception) {
			throw provenanceInvalid("The approved evidence contains an invalid " + field + ".");
		}
	}

	private static boolean requiredBoolean(JsonNode object, String field) {
		var value = object.get(field);
		if (value == null || !value.isBoolean()) {
			throw provenanceInvalid("The approved evidence is missing boolean " + field + ".");
		}
		return value.asBoolean();
	}

	private static Instant requiredInstant(JsonNode object, String field) {
		var value = requiredText(object, field);
		try {
			return Instant.parse(value);
		}
		catch (RuntimeException exception) {
			throw provenanceInvalid("The approved evidence contains an invalid " + field + ".");
		}
	}

	private static CandidateEvidenceQueryService.RunSummary requiredRunSummary(JsonNode summary) {
		if (summary == null || !summary.isObject()) {
			throw provenanceInvalid("The approved evidence run summary is missing.");
		}
		var failureReason = summary.get("failure_reason");
		if (failureReason == null
				|| (!failureReason.isNull() && (!failureReason.isTextual() || failureReason.asText().isBlank()))) {
			throw provenanceInvalid("The approved evidence run summary failure_reason is invalid.");
		}
		return new CandidateEvidenceQueryService.RunSummary(requiredBoolean(summary, "analysis_complete"),
				requiredText(summary, "hand_result"), requiredNonNegativeLong(summary, "total_video_frames"),
				requiredNonNegativeLong(summary, "inference_frame_count"),
				requiredNonNegativeLong(summary, "frames_with_hand"),
				requiredNonNegativeLong(summary, "frames_without_hand"),
				requiredNonNegativeLong(summary, "left_hand_detection_count"),
				requiredNonNegativeLong(summary, "right_hand_detection_count"),
				requiredNonNegativeLong(summary, "invalid_landmark_count"),
				requiredNonNegativeLong(summary, "processing_duration_ms"), requiredInstant(summary, "started_at"),
				requiredInstant(summary, "completed_at"), failureReason.isNull() ? null : failureReason.asText(),
				requiredBoolean(summary, "retryable"));
	}

	private static TrainingDataExportException provenanceInvalid(String detail) {
		return new TrainingDataExportException("HAND_LANDMARK_EXPORT_EVIDENCE_INVALID",
				"The approved MediaPipe evidence is incomplete or mismatched (" + detail + "); export is blocked.",
				422);
	}

	private static TrainingDataExportException candidateInvalid(String detail) {
		return new TrainingDataExportException("HAND_LANDMARK_EXPORT_DATA_INVALID",
				"The selected MediaPipe CandidateAnnotation is invalid (" + detail + "); export is blocked.", 422);
	}

	private record PrimaryEvidence(String clipAssetSha256, String executionStatus, UUID aiJobId, UUID aiJobAttemptId,
			Integer attempt, UUID messageId, String inputSha256, String algorithmKey, String algorithmVersion,
			String modelSha256, String profileSha256, String resultSha256, long candidateCount,
			String candidateSetDigest, CandidateEvidenceQueryService.RunSummary runSummary) {
	}

	private record ActionInterval(long startMs, long endMs, String label) {
	}

	private record TrainingRow(UUID captureGroupId, UUID annotationRevisionId, UUID clipAssetId, String clipAssetSha256,
			int cameraNumber, CandidateAnnotationSnapshot candidate, PrimaryEvidence evidence,
			ActionInterval interval) {
	}

	private record ExportSnapshot(long eligibleEventCount, long eventsWithLandmarks, List<TrainingRow> rows,
			Map<String, Long> reasonCounts) {
	}

}
