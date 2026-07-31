package com.medicationvideo.platform.qualitycleaning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
public class PostCleaningService {

	private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

	private final CleaningRuleSetRepository ruleSetRepository;

	private final CleaningRunRepository runRepository;

	private final CleaningResultRepository resultRepository;

	private final AuditEventRecorder auditRecorder;

	PostCleaningService(CleaningRuleSetRepository ruleSetRepository, CleaningRunRepository runRepository,
			CleaningResultRepository resultRepository, AuditEventRecorder auditRecorder) {
		this.ruleSetRepository = ruleSetRepository;
		this.runRepository = runRepository;
		this.resultRepository = resultRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	public PostCleaningResultSummary evaluate(EvaluationCommand command, String requestId, String traceId) {
		var ruleSet = activeRuleSet();
		var inputFingerprint = inputFingerprint(command);
		var replay = this.runRepository.findByAnnotationRevisionIdAndCleaningRuleSetIdAndInputFingerprint(
				command.annotationRevisionId(), ruleSet.getCleaningRuleSetId(), inputFingerprint);
		if (replay.isPresent()) {
			var result = this.resultRepository.findByCleaningRunId(replay.get().getCleaningRunId())
				.orElseThrow(() -> new IllegalStateException("The completed cleaning run has no result."));
			return PostCleaningResultSummary.from(result);
		}

		var evaluation = evaluateRules(command);
		var evaluatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var run = this.runRepository
			.saveAndFlush(CleaningRun.complete(command, ruleSet, inputFingerprint, evaluatedAt));
		var result = this.resultRepository
			.saveAndFlush(CleaningResult.record(run, evaluation.outcome(), evaluation.reasonCodes(), evaluatedAt));
		Map<String, String> details = new LinkedHashMap<>();
		details.put("annotation_revision_id", command.annotationRevisionId().toString());
		details.put("annotation_review_id", command.annotationReviewId().toString());
		details.put("clip_asset_id", command.clipAssetId().toString());
		details.put("asset_sha256", command.assetSha256());
		details.put("document_sha256", command.documentSha256());
		details.put("label_schema_digest", command.revisionLabelSchemaDigest());
		details.put("rule_set_digest", ruleSet.getRuleSetDigest());
		details.put("input_fingerprint", inputFingerprint);
		details.put("outcome", evaluation.outcome().name());
		if (command.mediaReportId() != null) {
			details.put("media_report_id", command.mediaReportId().toString());
		}
		this.auditRecorder
			.record(new AuditEventCommand(evaluatedAt, AuditActorType.USER, command.actorId(), command.projectId(),
					"POST_ANNOTATION_CLEANING_COMPLETED", "CleaningResult", result.getCleaningResultId().toString(),
					ruleSet.getRuleSetVersion(), AuditOutcome.SUCCEEDED, "POST_CLEANING_" + evaluation.outcome().name(),
					requestId, traceId, command.authorizationBasis(), Map.copyOf(details)));
		return PostCleaningResultSummary.from(result);
	}

	private CleaningRuleSet activeRuleSet() {
		var ruleSet = this.ruleSetRepository
			.findByRuleSetKeyAndStatus(PostCleaningProfile.RULE_SET_KEY, CleaningRuleSetStatus.ACTIVE)
			.orElseThrow(() -> new IllegalStateException("The active post-cleaning rule set is unavailable."));
		if (!PostCleaningProfile.RULE_SET_VERSION.equals(ruleSet.getRuleSetVersion())
				|| !PostCleaningProfile.RULE_SET_DIGEST.equals(ruleSet.getRuleSetDigest())) {
			throw new IllegalStateException("The active post-cleaning rule set does not match the server profile.");
		}
		return ruleSet;
	}

	static RuleEvaluation evaluateRules(EvaluationCommand command) {
		var reasons = new LinkedHashSet<String>();
		if (!command.approvedReview()) {
			reasons.add("REVIEW_NOT_APPROVED");
		}
		if (!command.qualityQualified()) {
			reasons.add("QUALITY_REVIEW_NOT_QUALIFIED");
		}
		if (!isSha256(command.assetSha256())) {
			reasons.add("ASSET_INPUT_DIGEST_INVALID");
		}
		if (!isSha256(command.documentSha256())) {
			reasons.add("ANNOTATION_DOCUMENT_DIGEST_INVALID");
		}
		else if (!command.documentSha256().equals(command.recomputedDocumentSha256())) {
			reasons.add("ANNOTATION_DOCUMENT_DIGEST_MISMATCH");
		}
		if (!command.taskLabelSchemaId().equals(command.revisionLabelSchemaId())
				|| !command.taskLabelSchemaDigest().equals(command.revisionLabelSchemaDigest())) {
			reasons.add("LABEL_SCHEMA_MISMATCH");
		}
		if (command.segments().isEmpty()) {
			reasons.add("ANNOTATION_SEGMENTS_MISSING");
		}
		if (command.segments()
			.stream()
			.anyMatch(segment -> segment.startMs() < 0 || segment.endMs() <= segment.startMs())) {
			reasons.add("SEGMENT_TIME_RANGE_INVALID");
		}
		if (command.segments().stream().anyMatch(segment -> !command.allowedLabels().contains(segment.label()))) {
			reasons.add("PRIMARY_STEP_LABEL_NOT_ALLOWED");
		}
		if (hasOverlap(command.segments())) {
			reasons.add("PRIMARY_STEP_OVERLAP");
		}
		var hasAnyMediaFact = command.mediaReportId() != null || command.mediaInputDigest() != null
				|| command.mediaRecipeDigest() != null || command.verifiedDurationMicroseconds() != null;
		var hasCompleteMediaFact = command.mediaReportId() != null && isSha256(command.mediaInputDigest())
				&& isSha256(command.mediaRecipeDigest()) && command.verifiedDurationMicroseconds() != null;
		if (hasAnyMediaFact && !hasCompleteMediaFact) {
			reasons.add("VERIFIED_MEDIA_FACT_INCOMPLETE");
		}
		if (hasCompleteMediaFact && !Objects.equals(command.assetSha256(), command.mediaInputDigest())) {
			reasons.add("VERIFIED_MEDIA_INPUT_DIGEST_MISMATCH");
		}
		if (command.verifiedDurationMicroseconds() == null) {
			reasons.add("VERIFIED_MEDIA_DURATION_UNAVAILABLE");
		}
		else if (command.verifiedDurationMicroseconds() <= 0) {
			reasons.add("VERIFIED_MEDIA_DURATION_INVALID");
		}
		else {
			var maximumEndMilliseconds = command.verifiedDurationMicroseconds() / 1_000;
			if (command.segments().stream().anyMatch(segment -> segment.endMs() > maximumEndMilliseconds)) {
				reasons.add("SEGMENT_EXCEEDS_VERIFIED_MEDIA_DURATION");
			}
		}
		var reasonCodes = List.copyOf(reasons);
		var outcome = reasonCodes.stream().anyMatch(PostCleaningService::isRejectReason) ? CleaningOutcome.REJECT
				: reasonCodes.isEmpty() ? CleaningOutcome.PASS : CleaningOutcome.WARNING;
		return new RuleEvaluation(outcome, reasonCodes);
	}

	private static String inputFingerprint(EvaluationCommand command) {
		try {
			var canonical = new StringBuilder();
			appendFingerprintField(canonical, command.projectId());
			appendFingerprintField(canonical, command.clipAssetId());
			appendFingerprintField(canonical, command.assetSha256());
			appendFingerprintField(canonical, command.qualityReviewVersion());
			appendFingerprintField(canonical, command.qualityQualified());
			appendFingerprintField(canonical, command.annotationRevisionId());
			appendFingerprintField(canonical, command.annotationReviewId());
			appendFingerprintField(canonical, command.approvedReview());
			appendFingerprintField(canonical, command.documentSha256());
			appendFingerprintField(canonical, command.recomputedDocumentSha256());
			appendFingerprintField(canonical, command.taskLabelSchemaId());
			appendFingerprintField(canonical, command.taskLabelSchemaDigest());
			appendFingerprintField(canonical, command.revisionLabelSchemaId());
			appendFingerprintField(canonical, command.revisionLabelSchemaDigest());
			appendFingerprintField(canonical, command.mediaReportId());
			appendFingerprintField(canonical, command.mediaInputDigest());
			appendFingerprintField(canonical, command.mediaRecipeDigest());
			appendFingerprintField(canonical, command.verifiedDurationMicroseconds());
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static void appendFingerprintField(StringBuilder target, Object value) {
		var text = value == null ? "" : value.toString();
		target.append(text.length()).append(':').append(text).append('\n');
	}

	private static boolean hasOverlap(List<SegmentSnapshot> segments) {
		if (segments.size() < 2) {
			return false;
		}
		var ordered = segments.stream()
			.sorted(Comparator.comparingLong(SegmentSnapshot::startMs).thenComparingLong(SegmentSnapshot::endMs))
			.toList();
		var maximumPreviousEnd = ordered.getFirst().endMs();
		for (var index = 1; index < ordered.size(); index += 1) {
			var segment = ordered.get(index);
			if (segment.startMs() < maximumPreviousEnd) {
				return true;
			}
			maximumPreviousEnd = Math.max(maximumPreviousEnd, segment.endMs());
		}
		return false;
	}

	private static boolean isSha256(String value) {
		return value != null && SHA256_PATTERN.matcher(value).matches();
	}

	private static boolean isRejectReason(String reason) {
		return !"VERIFIED_MEDIA_DURATION_UNAVAILABLE".equals(reason);
	}

	public record EvaluationCommand(UUID projectId, UUID clipAssetId, String assetSha256, long qualityReviewVersion,
			boolean qualityQualified, UUID annotationRevisionId, UUID annotationReviewId, boolean approvedReview,
			String documentSha256, String recomputedDocumentSha256, UUID taskLabelSchemaId,
			String taskLabelSchemaDigest, UUID revisionLabelSchemaId, String revisionLabelSchemaDigest,
			Set<String> allowedLabels, List<SegmentSnapshot> segments, UUID mediaReportId, String mediaInputDigest,
			String mediaRecipeDigest, Long verifiedDurationMicroseconds, String actorId, String authorizationBasis) {

		public EvaluationCommand {
			Objects.requireNonNull(projectId);
			Objects.requireNonNull(clipAssetId);
			Objects.requireNonNull(annotationRevisionId);
			Objects.requireNonNull(annotationReviewId);
			Objects.requireNonNull(taskLabelSchemaId);
			Objects.requireNonNull(revisionLabelSchemaId);
			Objects.requireNonNull(actorId);
			Objects.requireNonNull(authorizationBasis);
			allowedLabels = Set.copyOf(allowedLabels);
			segments = List.copyOf(segments);
		}
	}

	public record SegmentSnapshot(long startMs, long endMs, String label) {

		public SegmentSnapshot {
			label = label == null ? "" : label.trim();
		}
	}

	record RuleEvaluation(CleaningOutcome outcome, List<String> reasonCodes) {
	}

}
