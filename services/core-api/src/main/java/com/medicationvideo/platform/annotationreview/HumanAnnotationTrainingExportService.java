package com.medicationvideo.platform.annotationreview;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

/** Exports the current, approved human action annotations as training labels. */
@Service
class HumanAnnotationTrainingExportService {

	static final String SCHEMA_VERSION = "human-annotations.v1";

	static final String MEDIA_TYPE = "text/csv; charset=UTF-8";

	static final List<String> CSV_COLUMNS = List.of("capture_group_id", "annotation_task_id", "annotation_revision_id",
			"revision_number", "camera_stream_id", "primary_clip_asset_id", "primary_camera_number",
			"clip_asset_sha256", "annotation_schema_version", "label_schema_id", "label_schema_digest", "input_version",
			"segment_start_ms", "segment_end_ms", "label", "annotation_note", "document_sha256", "submitted_at",
			"annotation_review_id", "review_decision", "reviewed_at");

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private static final String SHA256_PATTERN = "^[a-f0-9]{64}$";

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final AnnotationTaskRepository taskRepository;

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationReviewRepository reviewRepository;

	private final CaptureGroupQualityQueryService groupQualityQueryService;

	private final QualityReviewQueryService viewQualityQueryService;

	private final MediaDurationQueryService mediaDurationQueryService;

	private final String dataProfile;

	HumanAnnotationTrainingExportService(CaptureGroupViewQueryService captureGroupViewQueryService,
			AnnotationTaskRepository taskRepository, AnnotationRevisionRepository revisionRepository,
			AnnotationReviewRepository reviewRepository, CaptureGroupQualityQueryService groupQualityQueryService,
			QualityReviewQueryService viewQualityQueryService, MediaDurationQueryService mediaDurationQueryService,
			@Value("${platform.training-data-export.data-profile:disabled}") String dataProfile) {
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.taskRepository = taskRepository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
		this.groupQualityQueryService = groupQualityQueryService;
		this.viewQualityQueryService = viewQualityQueryService;
		this.mediaDurationQueryService = mediaDurationQueryService;
		this.dataProfile = dataProfile == null ? "" : dataProfile.trim().toLowerCase(Locale.ROOT);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	byte[] download(UUID projectId, UUID captureGroupId) {
		var rows = snapshot(projectId, captureGroupId);
		if (rows.isEmpty()) {
			throw new AnnotationTaskException("HUMAN_ANNOTATION_EXPORT_NOT_READY",
					"No current human annotation has been approved for export.", 422);
		}
		return toCsv(rows);
	}

	static String fileName(UUID captureGroupId, Instant generatedAt) {
		var scope = captureGroupId == null ? "project" : "event_" + captureGroupId.toString().substring(0, 8);
		return "human_annotations_v1_%s_%s.csv".formatted(scope, FILE_TIMESTAMP.format(generatedAt));
	}

	private List<AnnotationRow> snapshot(UUID projectId, UUID captureGroupId) {
		requireSyntheticProfile();
		var groups = captureGroupId == null ? this.captureGroupViewQueryService.findAll(projectId)
				: List.of(this.captureGroupViewQueryService.get(projectId, captureGroupId));
		var factsByGroupId = groups.stream()
			.collect(Collectors.toUnmodifiableMap(CaptureGroupFacts::captureGroupId, facts -> facts));
		var tasks = this.taskRepository.findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId)
			.stream()
			.filter(task -> captureGroupId == null || captureGroupId.equals(task.getCaptureGroupId()))
			.toList();
		if (tasks.isEmpty()) {
			return List.of();
		}

		var tasksById = tasks.stream()
			.collect(Collectors.toUnmodifiableMap(AnnotationTask::getAnnotationTaskId, task -> task));
		var revisionsByTaskId = this.revisionRepository.findAllByAnnotationTaskIdIn(tasksById.keySet())
			.stream()
			.filter(revision -> {
				var task = tasksById.get(revision.getAnnotationTaskId());
				return task != null && task.isRevisionForCurrentGroupInput(revision);
			})
			.collect(Collectors.toMap(AnnotationRevision::getAnnotationTaskId, revision -> revision,
					(left, right) -> left.getRevisionNumber() >= right.getRevisionNumber() ? left : right));
		if (revisionsByTaskId.isEmpty()) {
			return List.of();
		}

		var reviewsByRevisionId = this.reviewRepository
			.findAllByAnnotationRevisionIdIn(
					revisionsByTaskId.values().stream().map(AnnotationRevision::getAnnotationRevisionId).toList())
			.stream()
			.collect(Collectors.toUnmodifiableMap(AnnotationReview::getAnnotationRevisionId, review -> review));

		var groupQualityById = this.groupQualityQueryService
			.getCurrentDecisions(projectId, groups.stream().map(CaptureGroupFacts::captureGroupId).toList())
			.stream()
			.collect(Collectors.toMap(CaptureGroupQualityQueryService.CurrentDecision::captureGroupId,
					decision -> decision));
		var viewQualityById = this.viewQualityQueryService.findCurrentDecisions(projectId,
				groups.stream()
					.flatMap(group -> group.views().stream())
					.map(CaptureGroupView::clipAssetId)
					.filter(Objects::nonNull)
					.distinct()
					.toList());
		var rows = new ArrayList<AnnotationRow>();
		for (var task : tasks) {
			var revision = revisionsByTaskId.get(task.getAnnotationTaskId());
			var review = revision == null ? null : reviewsByRevisionId.get(revision.getAnnotationRevisionId());
			if (revision == null || task.getStatus() != AnnotationTaskStatus.COMPLETED || review == null
					|| review.getDecision() != AnnotationReviewDecision.APPROVED) {
				continue;
			}
			var facts = factsByGroupId.get(task.getCaptureGroupId());
			if (facts == null) {
				throw invalidExport("The approved annotation no longer has a current CaptureGroup view.");
			}
			var groupQuality = groupQualityById.get(task.getCaptureGroupId());
			if (groupQuality == null || !groupQuality.isAdmitting()
					|| !Objects.equals(groupQuality.inputVersion(), revision.getInputVersion())
					|| !Objects.equals(groupQuality.confirmedViewSet(), revision.getConfirmedViewSet())
					|| !CaptureGroupAnnotationWorkflow.viewSetStillCurrent(revision.getConfirmedViewSet(), facts,
							viewQualityById)) {
				throw invalidExport("The current quality decisions or media inputs have changed since approval.");
			}
			var primaryView = facts.views()
				.stream()
				.filter(view -> Objects.equals(view.clipAssetId(), revision.getPrimaryClipAssetId()))
				.findFirst()
				.orElseThrow(() -> invalidExport("The approved annotation no longer has a current primary view."));
			validateCurrentPrimaryView(revision, review, primaryView);
			var duration = this.mediaDurationQueryService.findVerifiedDurationMicroseconds(projectId,
					primaryView.clipAssetId());
			if (duration.isEmpty()) {
				throw invalidExport("The primary media duration has not been verified.");
			}
			var annotationRows = annotationRows(task, revision, review, primaryView);
			if (annotationRows.stream().anyMatch(row -> row.segmentEndMs() > duration.getAsLong() / 1_000)) {
				throw invalidExport("An annotation segment exceeds the verified media duration.");
			}
			rows.addAll(annotationRows);
		}
		rows.sort(Comparator.comparing((AnnotationRow row) -> row.captureGroupId().toString())
			.thenComparingInt(AnnotationRow::primaryCameraNumber)
			.thenComparingLong(AnnotationRow::segmentStartMs)
			.thenComparingLong(AnnotationRow::segmentEndMs)
			.thenComparing(AnnotationRow::label)
			.thenComparing(row -> row.annotationRevisionId().toString()));
		return List.copyOf(rows);
	}

	private static void validateCurrentPrimaryView(AnnotationRevision revision, AnnotationReview review,
			CaptureGroupView primaryView) {
		if (!primaryView.present() || !primaryView.assetAvailable() || !"INGESTED".equals(primaryView.mediaStatus())
				|| !validSha256(primaryView.clipAssetSha256())
				|| !Objects.equals(primaryView.cameraNumber(), revision.getPrimaryCameraNumber())
				|| !Objects.equals(review.getCaptureGroupId(), revision.getCaptureGroupId())
				|| review.getRevisionNumber() != revision.getRevisionNumber()
				|| !Objects.equals(review.getPrimaryClipAssetId(), revision.getPrimaryClipAssetId())
				|| !Objects.equals(review.getInputVersion(), revision.getInputVersion())
				|| !Objects.equals(review.getConfirmedViewSet(), revision.getConfirmedViewSet())) {
			throw invalidExport("The approved annotation no longer matches the current media provenance.");
		}
	}

	private static List<AnnotationRow> annotationRows(AnnotationTask task, AnnotationRevision revision,
			AnnotationReview review, CaptureGroupView primaryView) {
		if (!"annotation-document.v1".equals(revision.getAnnotationSchemaVersion())
				|| !validSha256(revision.getDocumentSha256()) || !validSha256(revision.getLabelSchemaDigest())
				|| revision.getSubmittedAt() == null || review.getReviewedAt() == null) {
			throw invalidExport("The approved annotation provenance is incomplete.");
		}
		var document = revision.getAnnotationDocument();
		if (document == null || !"annotation-document.v1".equals(document.get("schema_version"))
				|| !(document.get("segments") instanceof List<?> segments)) {
			throw invalidExport("The annotation document does not match annotation-document.v1.");
		}
		var noteValue = document.get("note");
		if (noteValue != null && !(noteValue instanceof String)) {
			throw invalidExport("The annotation note is malformed.");
		}
		var note = noteValue == null ? "" : (String) noteValue;
		if (segments.isEmpty()) {
			throw invalidExport("The approved annotation contains no segments.");
		}

		var rows = new ArrayList<AnnotationRow>(segments.size());
		for (var value : segments) {
			if (!(value instanceof Map<?, ?> segment) || segment.size() != 3 || !segment.containsKey("start_ms")
					|| !segment.containsKey("end_ms") || !segment.containsKey("label")) {
				throw invalidExport("An annotation segment is malformed.");
			}
			var startMs = exactLong(segment.get("start_ms"));
			var endMs = exactLong(segment.get("end_ms"));
			var label = segment.get("label");
			if (startMs == null || endMs == null || startMs < 0 || endMs <= startMs || !(label instanceof String text)
					|| text.isBlank() || text.chars().anyMatch(Character::isISOControl)) {
				throw invalidExport("An annotation segment has invalid time or label data.");
			}
			rows.add(new AnnotationRow(revision.getCaptureGroupId(), task.getAnnotationTaskId(),
					revision.getAnnotationRevisionId(), revision.getRevisionNumber(), primaryView.cameraStreamId(),
					revision.getPrimaryClipAssetId(), revision.getPrimaryCameraNumber(), primaryView.clipAssetSha256(),
					revision.getAnnotationSchemaVersion(), revision.getLabelSchemaId(), revision.getLabelSchemaDigest(),
					revision.getInputVersion(), startMs, endMs, text, note, revision.getDocumentSha256(),
					revision.getSubmittedAt(), review.getAnnotationReviewId(), review.getDecision().name(),
					review.getReviewedAt()));
		}
		return rows;
	}

	private static byte[] toCsv(List<AnnotationRow> rows) {
		var csv = new StringBuilder();
		appendRecord(csv, CSV_COLUMNS);
		for (var row : rows) {
			appendRecord(csv,
					List.of(row.captureGroupId().toString(), row.annotationTaskId().toString(),
							row.annotationRevisionId().toString(), Integer.toString(row.revisionNumber()),
							row.cameraStreamId().toString(), row.primaryClipAssetId().toString(),
							Integer.toString(row.primaryCameraNumber()), row.clipAssetSha256(),
							row.annotationSchemaVersion(), row.labelSchemaId().toString(), row.labelSchemaDigest(),
							row.inputVersion(), Long.toString(row.segmentStartMs()), Long.toString(row.segmentEndMs()),
							row.label(), row.annotationNote(), row.documentSha256(), row.submittedAt().toString(),
							row.annotationReviewId().toString(), row.reviewDecision(), row.reviewedAt().toString()));
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
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

	private static Long exactLong(Object value) {
		if (!(value instanceof Number)) {
			return null;
		}
		try {
			return Long.parseLong(value.toString());
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

	private void requireSyntheticProfile() {
		if (!"synthetic".equals(this.dataProfile)) {
			throw new AnnotationTaskException("HUMAN_ANNOTATION_EXPORT_UNAVAILABLE",
					"Human annotation export is enabled only for the synthetic profile until a non-synthetic authorization model is defined.",
					403);
		}
	}

	private static AnnotationTaskException invalidExport(String detail) {
		return new AnnotationTaskException("HUMAN_ANNOTATION_EXPORT_DATA_INVALID",
				"The current approved human annotation is incomplete or mismatched (" + detail + ").", 422);
	}

	private static boolean validSha256(String value) {
		return value != null && value.matches(SHA256_PATTERN);
	}

	private record AnnotationRow(UUID captureGroupId, UUID annotationTaskId, UUID annotationRevisionId,
			int revisionNumber, UUID cameraStreamId, UUID primaryClipAssetId, int primaryCameraNumber,
			String clipAssetSha256, String annotationSchemaVersion, UUID labelSchemaId, String labelSchemaDigest,
			String inputVersion, long segmentStartMs, long segmentEndMs, String label, String annotationNote,
			String documentSha256, Instant submittedAt, UUID annotationReviewId, String reviewDecision,
			Instant reviewedAt) {
	}

}
