package com.medicationvideo.platform.qualitycleaning;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

import tools.jackson.databind.JsonNode;

/**
 * Deterministic event-level cleaning for CaptureGroup AnnotationRevisions.
 *
 * <p>
 * This service intentionally has no dependency on annotationreview persistence. The
 * caller supplies the immutable review and current group facts through the command, while
 * this module owns the cleaning fact. Raw media is never changed by this service.
 */
@Service
public class CaptureGroupCleaningService {

	public static final String RULE_SET_KEY = "capture-group-event-eligibility";

	public static final String RULE_SET_VERSION = "v1";

	public static final String RULE_SET_DIGEST = "9b0f46e8e6a0a4a2f2d31ef6c2cb2b7d0b7fca0a20a7e6b3f6f4a9d4b7f0a61c";

	private static final List<String> EXECUTED_CHECKS = List.of("REVIEW_APPROVED", "GROUP_QUALITY_ADMITTING",
			"PRIMARY_VIEW_AUTHORITATIVE_INTERVAL", "QUALIFIED_VIEWS_ONLY", "SUPPORTING_VIEWS_EVENT_LEVEL_EVIDENCE",
			"RAW_MEDIA_RETAINED");

	private final CaptureGroupCleaningRepository repository;

	private final AuditEventRecorder auditRecorder;

	CaptureGroupCleaningService(CaptureGroupCleaningRepository repository, AuditEventRecorder auditRecorder) {
		this.repository = repository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	public CaptureGroupCleaningSummary evaluate(EvaluationCommand command, String requestId, String traceId) {
		var inputFingerprint = inputFingerprint(command);
		var replay = this.repository.findByAnnotationRevisionIdAndInputFingerprint(command.annotationRevisionId(),
				inputFingerprint);
		if (replay.isPresent()) {
			return CaptureGroupCleaningSummary.from(replay.get());
		}
		var evaluation = evaluateEligibility(command);
		var evaluatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var cleaning = this.repository
			.saveAndFlush(CaptureGroupCleaning.record(command, evaluation, inputFingerprint, evaluatedAt));
		var details = new LinkedHashMap<String, String>();
		details.put("capture_group_id", command.captureGroupId().toString());
		details.put("annotation_revision_id", command.annotationRevisionId().toString());
		details.put("annotation_review_id", command.annotationReviewId().toString());
		details.put("group_quality_revision", Long.toString(command.groupQualityRevision()));
		details.put("participating_view_count", Integer.toString(evaluation.participatingViews().size()));
		details.put("excluded_view_count", Integer.toString(evaluation.excludedViews().size()));
		details.put("input_fingerprint", inputFingerprint);
		details.put("outcome", evaluation.outcome().name());
		this.auditRecorder.record(new AuditEventCommand(evaluatedAt, AuditActorType.USER, command.actorId(),
				command.projectId(), "CAPTURE_GROUP_EVENT_CLEANING_COMPLETED", "CaptureGroupCleaning",
				cleaning.getCaptureGroupCleaningId().toString(), RULE_SET_VERSION, AuditOutcome.SUCCEEDED,
				"CAPTURE_GROUP_CLEANING_" + evaluation.outcome().name(), requestId, traceId,
				command.authorizationBasis(), Map.copyOf(details)));
		return CaptureGroupCleaningSummary.from(cleaning);
	}

	/**
	 * Pure event cleaning evaluation used by the application service and focused contract
	 * tests.
	 */
	public static EligibilityEvaluation evaluateEligibility(EvaluationCommand command) {
		var reasons = new LinkedHashSet<String>();
		if (!command.approvedReview()) {
			reasons.add("REVIEW_NOT_APPROVED");
		}
		if (!isAdmitting(command.groupQualityResult())) {
			reasons.add("GROUP_QUALITY_NOT_ADMITTING");
		}
		var primary = command.views()
			.stream()
			.filter(view -> command.primaryClipAssetId().equals(view.clipAssetId()))
			.findFirst()
			.orElse(null);
		if (primary == null) {
			reasons.add("PRIMARY_VIEW_MISSING");
		}
		else if (!isParticipating(primary)) {
			reasons.add("PRIMARY_VIEW_NOT_QUALIFIED");
		}
		var primaryInterval = primaryInterval(command.primarySegments(), reasons);
		var viewEvidence = new ArrayList<Map<String, Object>>();
		var participatingViews = new ArrayList<Map<String, Object>>();
		var excludedViews = new ArrayList<Map<String, Object>>();
		var mappingProvenance = new ArrayList<Map<String, Object>>();
		var seenCameraNumbers = new LinkedHashSet<Integer>();
		for (var view : command.views().stream().sorted(Comparator.comparingInt(ViewInput::cameraNumber)).toList()) {
			if (!seenCameraNumbers.add(view.cameraNumber())) {
				reasons.add("VIEW_SET_DUPLICATE_CAMERA_NUMBER");
			}
			var participating = isParticipating(view);
			var evidence = baseViewEvidence(view);
			evidence.put("retained", true);
			evidence.put("downstream_included", participating);
			if (!participating) {
				evidence.put("interval_source", "EVENT_LEVEL_EVIDENCE");
				evidence.put("action_aligned_interval", false);
				evidence.put("exclusion_reason", exclusionReason(view));
				excludedViews.add(immutableMap(evidence));
				viewEvidence.add(immutableMap(evidence));
				continue;
			}
			if (command.primaryClipAssetId().equals(view.clipAssetId())) {
				evidence.put("interval", primaryInterval);
				evidence.put("interval_source", "PRIMARY_AUTHORITATIVE");
				evidence.put("action_aligned_interval", true);
			}
			else {
				evidence.put("interval_source", "EVENT_LEVEL_EVIDENCE");
				evidence.put("action_aligned_interval", false);
			}
			participatingViews.add(immutableMap(evidence));
			viewEvidence.add(immutableMap(evidence));
		}
		if (participatingViews.isEmpty()) {
			reasons.add("NO_QUALIFIED_VIEWS");
		}
		var outcome = reasons.isEmpty() ? CleaningOutcome.PASS : CleaningOutcome.REJECT;
		return new EligibilityEvaluation(outcome, List.copyOf(reasons), EXECUTED_CHECKS, List.copyOf(primaryInterval),
				List.copyOf(viewEvidence), List.copyOf(participatingViews), List.copyOf(excludedViews),
				List.copyOf(mappingProvenance));
	}

	private static boolean isAdmitting(String result) {
		return "QUALIFIED".equals(result) || "QUALIFIED_WITH_EXCLUSIONS".equals(result);
	}

	private static boolean isParticipating(ViewInput view) {
		return view.present() && view.assetAvailable() && view.reviewable()
				&& "QUALIFIED".equals(view.qualityDecision());
	}

	private static List<Map<String, Object>> primaryInterval(List<SegmentSnapshot> segments, Set<String> reasons) {
		var interval = new ArrayList<Map<String, Object>>();
		if (segments.isEmpty()) {
			reasons.add("PRIMARY_INTERVAL_MISSING");
			return interval;
		}
		var ordered = segments.stream()
			.sorted(Comparator.comparingLong(SegmentSnapshot::startMs).thenComparingLong(SegmentSnapshot::endMs))
			.toList();
		var previousEnd = -1L;
		for (var segment : ordered) {
			if (segment.startMs() < 0 || segment.endMs() <= segment.startMs() || segment.label().isBlank()) {
				reasons.add("PRIMARY_INTERVAL_INVALID");
				continue;
			}
			if (segment.startMs() < previousEnd) {
				reasons.add("PRIMARY_INTERVAL_OVERLAP");
			}
			previousEnd = Math.max(previousEnd, segment.endMs());
			var value = new LinkedHashMap<String, Object>();
			value.put("start_ms", segment.startMs());
			value.put("end_ms", segment.endMs());
			value.put("label", segment.label());
			interval.add(Map.copyOf(value));
		}
		return interval;
	}

	private static Map<String, Object> baseViewEvidence(ViewInput view) {
		var value = new LinkedHashMap<String, Object>();
		value.put("camera_number", view.cameraNumber());
		if (view.cameraStreamId() != null) {
			value.put("camera_stream_id", view.cameraStreamId().toString());
		}
		if (view.clipAssetId() != null) {
			value.put("clip_asset_id", view.clipAssetId().toString());
		}
		value.put("present", view.present());
		value.put("media_status", view.mediaStatus());
		value.put("technical_quality_status", view.technicalQualityStatus());
		value.put("asset_available", view.assetAvailable());
		value.put("quality_decision", view.qualityDecision());
		value.put("quality_review_version", view.qualityReviewVersion());
		value.put("declared_sync_status", view.declaredSyncStatus());
		value.put("sync_reliability", normalizeReliability(view.declaredSyncStatus()));
		value.put("stream_version", view.streamVersion());
		value.put("clip_asset_version", view.clipAssetVersion());
		if (view.clipAssetSha256() != null) {
			value.put("clip_asset_sha256", view.clipAssetSha256());
		}
		return value;
	}

	private static String exclusionReason(ViewInput view) {
		if (!view.present()) {
			return "MISSING_VIEW";
		}
		if (!view.assetAvailable() || !view.reviewable()) {
			return "VIEW_NOT_REVIEWABLE";
		}
		return "VIEW_QUALITY_NOT_QUALIFIED";
	}

	private static Map<String, Object> immutableMap(Map<String, Object> value) {
		return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
	}

	private static String normalizeReliability(String declaredSyncStatus) {
		return switch (declaredSyncStatus == null ? "" : declaredSyncStatus) {
			case "PASS", "RELIABLE" -> "RELIABLE";
			case "WARNING", "APPROXIMATE" -> "APPROXIMATE";
			default -> "UNRELIABLE";
		};
	}

	private static String inputFingerprint(EvaluationCommand command) {
		try {
			var canonical = new StringBuilder();
			append(canonical, command.projectId());
			append(canonical, command.captureGroupId());
			append(canonical, command.annotationRevisionId());
			append(canonical, command.annotationReviewId());
			append(canonical, command.approvedReview());
			append(canonical, command.groupQualityReviewId());
			append(canonical, command.groupQualityResult());
			append(canonical, command.groupQualityRevision());
			append(canonical, command.primaryClipAssetId());
			append(canonical, command.primaryCameraNumber());
			append(canonical, "candidate-evidence.v1");
			append(canonical, "candidate_evidence");
			command.candidateEvidence()
				.stream()
				.map(CaptureGroupCleaningService::canonicalCandidateEvidence)
				.sorted()
				.forEach(value -> append(canonical, value));
			append(canonical, "candidate_evidence_end");
			for (var segment : command.primarySegments()) {
				append(canonical, segment.startMs());
				append(canonical, segment.endMs());
				append(canonical, segment.label());
			}
			for (var view : command.views()
				.stream()
				.sorted(Comparator.comparingInt(ViewInput::cameraNumber))
				.toList()) {
				append(canonical, view.cameraNumber());
				append(canonical, view.cameraStreamId());
				append(canonical, view.clipAssetId());
				append(canonical, view.present());
				append(canonical, view.mediaStatus());
				append(canonical, view.technicalQualityStatus());
				append(canonical, view.assetAvailable());
				append(canonical, view.qualityDecision());
				append(canonical, view.qualityReviewVersion());
				append(canonical, view.declaredSyncStatus());
				append(canonical, view.clipAssetSha256());
				append(canonical, view.streamVersion());
				append(canonical, view.clipAssetVersion());
			}
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static String canonicalCandidateEvidence(Map<String, Object> evidence) {
		var canonical = new StringBuilder();
		appendCanonicalValue(canonical, evidence);
		return canonical.toString();
	}

	private static void appendCanonicalValue(StringBuilder target, Object value) {
		if (value == null) {
			target.append("null");
		}
		else if (value instanceof JsonNode node) {
			appendCanonicalJson(target, node);
		}
		else if (value instanceof Map<?, ?> map) {
			var entries = map.entrySet().stream().map(entry -> {
				if (!(entry.getKey() instanceof String key)) {
					throw new IllegalArgumentException("Candidate evidence map keys must be strings.");
				}
				return new java.util.AbstractMap.SimpleImmutableEntry<>(key, entry.getValue());
			}).sorted(Map.Entry.comparingByKey()).toList();
			target.append("map[").append(entries.size()).append("]{");
			for (var entry : entries) {
				appendCanonicalString(target, entry.getKey());
				appendCanonicalValue(target, entry.getValue());
			}
			target.append('}');
		}
		else if (value instanceof Set<?> set) {
			var values = set.stream().map(CaptureGroupCleaningService::canonicalValue).sorted().toList();
			target.append("set[").append(values.size()).append("]{");
			values.forEach(target::append);
			target.append('}');
		}
		else if (value instanceof Iterable<?> iterable) {
			var values = new ArrayList<String>();
			for (var item : iterable) {
				values.add(canonicalValue(item));
			}
			target.append("list[").append(values.size()).append("]{");
			values.forEach(target::append);
			target.append('}');
		}
		else if (value.getClass().isArray()) {
			var length = java.lang.reflect.Array.getLength(value);
			target.append("array[").append(length).append("]{");
			for (var index = 0; index < length; index++) {
				target.append(canonicalValue(java.lang.reflect.Array.get(value, index)));
			}
			target.append('}');
		}
		else {
			appendCanonicalScalar(target, value);
		}
	}

	private static String canonicalValue(Object value) {
		var canonical = new StringBuilder();
		appendCanonicalValue(canonical, value);
		return canonical.toString();
	}

	private static void appendCanonicalJson(StringBuilder target, JsonNode node) {
		if (node == null || node.isNull()) {
			target.append("null");
		}
		else if (node.isObject()) {
			var names = new ArrayList<String>();
			node.propertyNames().forEach(names::add);
			names.sort(String::compareTo);
			target.append("map[").append(names.size()).append("]{");
			for (var name : names) {
				appendCanonicalString(target, name);
				appendCanonicalJson(target, node.get(name));
			}
			target.append('}');
		}
		else if (node.isArray()) {
			target.append("list[").append(node.size()).append("]{");
			for (var child : node) {
				appendCanonicalJson(target, child);
			}
			target.append('}');
		}
		else if (node.isNumber()) {
			target.append("number[")
				.append(new BigDecimal(node.asText()).stripTrailingZeros().toPlainString())
				.append(']');
		}
		else if (node.isBoolean()) {
			target.append(node.asBoolean() ? "boolean[true]" : "boolean[false]");
		}
		else {
			appendCanonicalString(target, node.asText());
		}
	}

	private static void appendCanonicalScalar(StringBuilder target, Object value) {
		if (value instanceof BigDecimal decimal) {
			target.append("number[").append(decimal.stripTrailingZeros().toPlainString()).append(']');
		}
		else if (value instanceof Number number) {
			target.append("number[")
				.append(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString())
				.append(']');
		}
		else if (value instanceof Boolean booleanValue) {
			target.append(booleanValue ? "boolean[true]" : "boolean[false]");
		}
		else if (value instanceof UUID || value instanceof Character || value instanceof String) {
			appendCanonicalString(target, value.toString());
		}
		else if (value instanceof Enum<?> enumValue) {
			appendCanonicalString(target, enumValue.name());
		}
		else {
			throw new IllegalArgumentException("Unsupported candidate evidence value: " + value.getClass().getName());
		}
	}

	private static void appendCanonicalString(StringBuilder target, String value) {
		target.append("string[").append(value.length()).append(':').append(value).append(']');
	}

	private static void append(StringBuilder target, Object value) {
		var text = value == null ? "" : value.toString();
		target.append(text.length()).append(':').append(text).append('\n');
	}

	public record EvaluationCommand(UUID projectId, UUID captureGroupId, UUID annotationRevisionId,
			UUID annotationReviewId, boolean approvedReview, String reviewedBy, Instant reviewedAt,
			UUID groupQualityReviewId, String groupQualityResult, long groupQualityRevision, UUID primaryClipAssetId,
			int primaryCameraNumber, List<SegmentSnapshot> primarySegments, List<ViewInput> views,
			List<Map<String, Object>> candidateEvidence, String actorId, String authorizationBasis) {

		public EvaluationCommand {
			Objects.requireNonNull(projectId);
			Objects.requireNonNull(captureGroupId);
			Objects.requireNonNull(annotationRevisionId);
			Objects.requireNonNull(annotationReviewId);
			Objects.requireNonNull(reviewedBy);
			Objects.requireNonNull(reviewedAt);
			Objects.requireNonNull(groupQualityReviewId);
			Objects.requireNonNull(groupQualityResult);
			Objects.requireNonNull(primaryClipAssetId);
			Objects.requireNonNull(actorId);
			Objects.requireNonNull(authorizationBasis);
			primarySegments = List.copyOf(primarySegments);
			views = List.copyOf(views);
			candidateEvidence = List.copyOf(candidateEvidence);
		}
	}

	public record SegmentSnapshot(long startMs, long endMs, String label) {

		public SegmentSnapshot {
			label = label == null ? "" : label.trim();
		}
	}

	public record ViewInput(UUID cameraStreamId, UUID clipAssetId, int cameraNumber, boolean present,
			String mediaStatus, String technicalQualityStatus, long streamVersion, long clipAssetVersion,
			boolean assetAvailable, String qualityDecision, long qualityReviewVersion, String declaredSyncStatus,
			String clipAssetSha256) {

		public ViewInput(UUID cameraStreamId, UUID clipAssetId, int cameraNumber, boolean present, String mediaStatus,
				String technicalQualityStatus, long streamVersion, long clipAssetVersion, boolean assetAvailable,
				String qualityDecision, long qualityReviewVersion, String declaredSyncStatus) {
			this(cameraStreamId, clipAssetId, cameraNumber, present, mediaStatus, technicalQualityStatus, streamVersion,
					clipAssetVersion, assetAvailable, qualityDecision, qualityReviewVersion, declaredSyncStatus, null);
		}

		public boolean reviewable() {
			return "INGESTED".equals(mediaStatus);
		}
	}

	public record EligibilityEvaluation(CleaningOutcome outcome, List<String> reasonCodes, List<String> executedChecks,
			List<Map<String, Object>> primaryInterval, List<Map<String, Object>> viewEvidence,
			List<Map<String, Object>> participatingViews, List<Map<String, Object>> excludedViews,
			List<Map<String, Object>> mappingProvenance) {

		public EligibilityEvaluation {
			reasonCodes = List.copyOf(reasonCodes);
			executedChecks = List.copyOf(executedChecks);
			primaryInterval = List.copyOf(primaryInterval);
			viewEvidence = List.copyOf(viewEvidence);
			participatingViews = List.copyOf(participatingViews);
			excludedViews = List.copyOf(excludedViews);
			mappingProvenance = List.copyOf(mappingProvenance);
		}
	}

}
