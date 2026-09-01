package com.medicationvideo.platform.aicandidate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only candidate provenance exposed to the CaptureGroup annotation review boundary.
 *
 * <p>
 * Candidate evidence is advisory only. This query proves the input and execution identity
 * of the latest MediaPipe job for each requested ClipAsset, but it never represents a
 * formal annotation decision and cannot approve a Review or Cleaning outcome.
 */
@Service
public class CandidateEvidenceQueryService {

	static final String EXECUTION_STATUS_NO_JOB = "NO_JOB";

	static final String EXECUTION_STATUS_QUEUED = "QUEUED";

	static final String EXECUTION_STATUS_RUNNING = "RUNNING";

	static final String EXECUTION_STATUS_FAILED = "FAILED";

	static final String EXECUTION_STATUS_SUCCEEDED_NO_HAND = "SUCCEEDED_NO_HAND";

	static final String EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES = "SUCCEEDED_WITH_CANDIDATES";

	static final String EXECUTION_STATUS_INVALID_PROVENANCE = "INVALID_PROVENANCE";

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	CandidateEvidenceQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Returns one evidence snapshot per requested ClipAsset, including a valid NO_JOB
	 * state. The current ClipAsset row is deliberately read without a project predicate
	 * so a project/asset mismatch can be represented as invalid provenance instead of
	 * being silently treated as no evidence.
	 */
	@Transactional(readOnly = true)
	public List<CandidateEvidence> findByClipAssets(UUID projectId, Collection<UUID> clipAssetIds) {
		Objects.requireNonNull(projectId);
		var ids = clipAssetIds.stream().filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return List.of();
		}

		var assets = this.jdbcTemplate.query(assetQuery(ids), this::mapAsset, ids.toArray())
			.stream()
			.collect(Collectors.toMap(ClipAssetFact::clipAssetId, fact -> fact));
		var jobs = this.jdbcTemplate.query(latestJobQuery(ids), this::mapJob, ids.toArray())
			.stream()
			.collect(Collectors.toMap(JobFact::clipAssetId, fact -> fact));

		var candidatesByJob = loadCandidates(jobs.values());
		return ids.stream().map(clipAssetId -> {
			var job = jobs.get(clipAssetId);
			var candidates = job == null ? List.<CandidateAnnotationSnapshot>of()
					: candidatesByJob.getOrDefault(job.aiJobId(), List.of());
			return evidenceFor(projectId, clipAssetId, assets.get(clipAssetId), job, candidates);
		}).toList();
	}

	/**
	 * Compatibility name for callers that still describe this query as latest-successful.
	 * The returned snapshot now includes every execution state and validates input
	 * identity.
	 */
	@Transactional(readOnly = true)
	public List<CandidateEvidence> findLatestSuccessfulByClipAssets(UUID projectId, Collection<UUID> clipAssetIds) {
		return findByClipAssets(projectId, clipAssetIds);
	}

	private Map<UUID, List<CandidateAnnotationSnapshot>> loadCandidates(Collection<JobFact> jobs) {
		if (jobs.isEmpty()) {
			return Map.of();
		}
		var jobIds = jobs.stream().map(JobFact::aiJobId).toList();
		var candidates = this.jdbcTemplate.query(candidateQuery(jobIds), this::mapCandidate, jobIds.toArray());
		var byJob = new HashMap<UUID, List<CandidateAnnotationSnapshot>>();
		for (var candidate : candidates) {
			byJob.computeIfAbsent(candidate.aiJobId(), ignored -> new ArrayList<>()).add(candidate.snapshot());
		}
		return byJob.entrySet()
			.stream()
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
	}

	private CandidateEvidence evidenceFor(UUID requestedProjectId, UUID requestedClipAssetId, ClipAssetFact asset,
			JobFact job, List<CandidateAnnotationSnapshot> candidates) {
		if (asset == null) {
			return invalid(requestedClipAssetId, null, null, "CURRENT_CLIP_ASSET_MISSING");
		}
		if (!requestedProjectId.equals(asset.projectId())) {
			return invalid(requestedClipAssetId, null, null, "PROJECT_MISMATCH");
		}
		if (job == null) {
			return new CandidateEvidence(requestedClipAssetId, asset.sha256(), true, EXECUTION_STATUS_NO_JOB, null,
					null, null, null, null, null, null, null, null, null, null, null, 0, null, null, null, null);
		}
		if (!requestedProjectId.equals(job.projectId())) {
			return invalid(requestedClipAssetId, asset.sha256(), null, "JOB_PROJECT_MISMATCH");
		}
		if (!requestedClipAssetId.equals(job.clipAssetId())) {
			return invalid(requestedClipAssetId, asset.sha256(), job, "JOB_CLIP_ASSET_MISMATCH");
		}
		if (!Objects.equals(asset.sha256(), job.inputSha256())) {
			return invalid(requestedClipAssetId, asset.sha256(), job, "INPUT_SHA256_MISMATCH", candidates);
		}
		if (!HandPoseProfile.ALGORITHM_KEY.equals(job.algorithmKey())) {
			return invalid(requestedClipAssetId, asset.sha256(), job, "ALGORITHM_MISMATCH", candidates);
		}
		if (candidates.stream()
			.anyMatch(candidate -> !requestedProjectId.equals(candidate.projectId())
					|| !requestedClipAssetId.equals(candidate.clipAssetId())
					|| !job.aiJobId().equals(candidate.aiJobId()))) {
			return invalid(requestedClipAssetId, asset.sha256(), job, "CANDIDATE_BINDING_MISMATCH", candidates);
		}
		if (candidates.stream()
			.anyMatch(candidate -> candidate.boundingBox() == null || candidate.landmarks() == null)) {
			return invalid(requestedClipAssetId, asset.sha256(), job, "CANDIDATE_GEOMETRY_INVALID", candidates);
		}
		if (job.attempt() == null) {
			return invalid(requestedClipAssetId, asset.sha256(), job, "ATTEMPT_MISSING", candidates);
		}
		if (!Objects.equals(job.status(), job.attemptStatus())) {
			return invalid(requestedClipAssetId, asset.sha256(), job, "ATTEMPT_STATUS_MISMATCH", candidates);
		}

		var candidateCount = candidates.size();
		var candidateSetDigest = candidateSetDigest(candidates);
		return switch (job.status()) {
			case "QUEUED" -> candidateCount == 0
					? validJob(job, asset, EXECUTION_STATUS_QUEUED, null, candidateCount, null, null, null) : invalid(
							requestedClipAssetId, asset.sha256(), job, "CANDIDATES_WITH_NON_TERMINAL_JOB", candidates);
			case "RUNNING" -> candidateCount == 0
					? validJob(job, asset, EXECUTION_STATUS_RUNNING, null, candidateCount, null, null, null) : invalid(
							requestedClipAssetId, asset.sha256(), job, "CANDIDATES_WITH_NON_TERMINAL_JOB", candidates);
			case "FAILED" -> candidateCount == 0
					? validJob(job, asset, EXECUTION_STATUS_FAILED, job.failureReason(), candidateCount, null, null,
							job.resultSha256())
					: invalid(requestedClipAssetId, asset.sha256(), job, "CANDIDATES_ON_FAILED_JOB", candidates);
			case "SUCCEEDED" -> successfulEvidence(job, asset, candidateCount, candidateSetDigest);
			default -> invalid(requestedClipAssetId, asset.sha256(), job, "JOB_STATUS_INVALID", candidates);
		};
	}

	private CandidateEvidence successfulEvidence(JobFact job, ClipAssetFact asset, int candidateCount,
			String candidateSetDigest) {
		var summary = runSummary(job);
		if (!Objects.equals(job.handResult(), job.attemptHandResult())) {
			return invalid(job.clipAssetId(), asset.sha256(), job, "HAND_RESULT_MISMATCH");
		}
		if (job.resultSha256() == null || job.messageId() == null || job.attemptId() == null
				|| candidateSetDigest == null || summary == null) {
			return invalid(job.clipAssetId(), asset.sha256(), job, "SUCCESS_RESULT_PROVENANCE_MISSING");
		}
		return switch (job.handResult()) {
			case "NO_HAND_DETECTED" -> candidateCount == 0
					? validJob(job, asset, EXECUTION_STATUS_SUCCEEDED_NO_HAND, null, candidateCount, null, null,
							candidateSetDigest)
					: invalid(job.clipAssetId(), asset.sha256(), job, "NO_HAND_WITH_CANDIDATES");
			case "HAND_DETECTED" -> candidateCount > 0
					? validJob(job, asset, EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES, null, candidateCount,
							job.firstTimestampMs(), job.lastTimestampMs(), candidateSetDigest)
					: invalid(job.clipAssetId(), asset.sha256(), job, "HAND_DETECTED_WITHOUT_CANDIDATES");
			default -> invalid(job.clipAssetId(), asset.sha256(), job, "SUCCESS_HAND_RESULT_INVALID");
		};
	}

	private CandidateEvidence validJob(JobFact job, ClipAssetFact asset, String executionStatus, String failureReason,
			long candidateCount, Long firstTimestampMs, Long lastTimestampMs, String candidateSetDigest) {
		return new CandidateEvidence(job.clipAssetId(), asset.sha256(), true, executionStatus, failureReason,
				job.aiJobId(), job.inputSha256(), job.algorithmKey(), job.algorithmVersion(), job.modelSha256(),
				job.profileSha256(), job.attemptId(), job.attempt(), job.messageId(), job.resultSha256(),
				job.handResult(), candidateCount, firstTimestampMs, lastTimestampMs, candidateSetDigest,
				runSummary(job));
	}

	private CandidateEvidence invalid(UUID clipAssetId, String clipAssetSha256, JobFact job, String reason) {
		return invalid(clipAssetId, clipAssetSha256, job, reason, List.of());
	}

	private CandidateEvidence invalid(UUID clipAssetId, String clipAssetSha256, JobFact job, String reason,
			List<CandidateAnnotationSnapshot> candidates) {
		if (job == null) {
			return new CandidateEvidence(clipAssetId, clipAssetSha256, false, EXECUTION_STATUS_INVALID_PROVENANCE,
					reason, null, null, null, null, null, null, null, null, null, null, null, 0, null, null, null,
					null);
		}
		var count = candidates.size();
		var first = count == 0 ? null
				: candidates.stream()
					.map(CandidateAnnotationSnapshot::frameTimestampMs)
					.min(Long::compare)
					.orElse(null);
		var last = count == 0 ? null
				: candidates.stream()
					.map(CandidateAnnotationSnapshot::frameTimestampMs)
					.max(Long::compare)
					.orElse(null);
		var digest = candidates.stream()
			.anyMatch(candidate -> candidate.boundingBox() == null || candidate.landmarks() == null) ? null
					: candidateSetDigest(candidates);
		return new CandidateEvidence(clipAssetId, clipAssetSha256, false, EXECUTION_STATUS_INVALID_PROVENANCE, reason,
				job.aiJobId(), job.inputSha256(), job.algorithmKey(), job.algorithmVersion(), job.modelSha256(),
				job.profileSha256(), job.attemptId(), job.attempt(), job.messageId(), job.resultSha256(),
				job.handResult(), count, first, last, digest, runSummary(job));
	}

	private static RunSummary runSummary(JobFact job) {
		if (job.startedAt() == null || job.completedAt() == null || job.analysisComplete() == null
				|| job.attemptHandResult() == null || job.totalVideoFrames() == null
				|| job.inferenceFrameCount() == null || job.framesWithHand() == null || job.framesWithoutHand() == null
				|| job.leftHandDetectionCount() == null || job.rightHandDetectionCount() == null
				|| job.invalidLandmarkCount() == null || job.processingDurationMs() == null
				|| job.retryable() == null) {
			return null;
		}
		return new RunSummary(job.analysisComplete(), job.attemptHandResult(), job.totalVideoFrames(),
				job.inferenceFrameCount(), job.framesWithHand(), job.framesWithoutHand(), job.leftHandDetectionCount(),
				job.rightHandDetectionCount(), job.invalidLandmarkCount(), job.processingDurationMs(), job.startedAt(),
				job.completedAt(), job.failureReason(), job.retryable());
	}

	private CandidateAnnotationFact mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CandidateAnnotationFact(resultSet.getObject("candidate_annotation_id", UUID.class),
				resultSet.getObject("ai_job_id", UUID.class), resultSet.getObject("project_id", UUID.class),
				resultSet.getObject("clip_asset_id", UUID.class), resultSet.getLong("frame_timestamp_ms"),
				resultSet.getInt("image_width"), resultSet.getInt("image_height"), resultSet.getInt("hand_index"),
				resultSet.getString("handedness"), resultSet.getBigDecimal("handedness_score"),
				readJson(resultSet.getString("bounding_box")), readJson(resultSet.getString("landmarks")),
				resultSet.getInt("valid_landmark_count"));
	}

	private ClipAssetFact mapAsset(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ClipAssetFact(resultSet.getObject("clip_asset_id", UUID.class),
				resultSet.getObject("project_id", UUID.class), resultSet.getString("sha256"));
	}

	private JobFact mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
		return new JobFact(resultSet.getObject("ai_job_id", UUID.class), resultSet.getObject("project_id", UUID.class),
				resultSet.getObject("clip_asset_id", UUID.class), resultSet.getString("algorithm_key"),
				resultSet.getString("algorithm_version"), resultSet.getString("model_sha256"),
				resultSet.getString("input_sha256"), resultSet.getString("profile_sha256"),
				resultSet.getString("status"), resultSet.getString("hand_result"),
				resultSet.getObject("ai_job_attempt_id", UUID.class), resultSet.getObject("attempt", Integer.class),
				resultSet.getObject("message_id", UUID.class), resultSet.getString("attempt_status"),
				resultSet.getString("attempt_hand_result"), resultSet.getObject("analysis_complete", Boolean.class),
				instant(resultSet, "started_at"), instant(resultSet, "completed_at"),
				resultSet.getObject("total_video_frames", Long.class),
				resultSet.getObject("inference_frame_count", Long.class),
				resultSet.getObject("frames_with_hand", Long.class),
				resultSet.getObject("frames_without_hand", Long.class),
				resultSet.getObject("left_hand_detection_count", Long.class),
				resultSet.getObject("right_hand_detection_count", Long.class),
				resultSet.getObject("invalid_landmark_count", Long.class),
				resultSet.getObject("processing_duration_ms", Long.class), resultSet.getString("failure_reason"),
				resultSet.getObject("retryable", Boolean.class), resultSet.getString("result_sha256"),
				resultSet.getObject("first_timestamp_ms", Long.class),
				resultSet.getObject("last_timestamp_ms", Long.class));
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private JsonNode readJson(String source) {
		if (source == null) {
			return null;
		}
		try {
			return this.objectMapper.readTree(source);
		}
		catch (JacksonException exception) {
			return null;
		}
	}

	static String candidateSetDigest(List<CandidateAnnotationSnapshot> candidates) {
		var sorted = candidates.stream()
			.sorted(Comparator.comparingLong(CandidateAnnotationSnapshot::frameTimestampMs)
				.thenComparingInt(CandidateAnnotationSnapshot::handIndex)
				.thenComparing(snapshot -> snapshot.candidateAnnotationId().toString()))
			.toList();
		var canonical = new StringBuilder("candidate-set.v1\n");
		appendToken(canonical, "count", sorted.size());
		for (var candidate : sorted) {
			appendToken(canonical, "candidate_annotation_id", candidate.candidateAnnotationId());
			appendToken(canonical, "frame_timestamp_ms", candidate.frameTimestampMs());
			appendToken(canonical, "image_width", candidate.imageWidth());
			appendToken(canonical, "image_height", candidate.imageHeight());
			appendToken(canonical, "hand_index", candidate.handIndex());
			appendToken(canonical, "handedness", candidate.handedness());
			appendToken(canonical, "handedness_score", candidate.handednessScore());
			appendToken(canonical, "valid_landmark_count", candidate.validLandmarkCount());
			canonical.append("bounding_box=");
			appendCanonicalJson(canonical, candidate.boundingBox());
			canonical.append('\n');
			canonical.append("landmarks=");
			appendCanonicalJson(canonical, candidate.landmarks());
			canonical.append('\n');
		}
		return HandPoseProfile.sha256(canonical.toString());
	}

	private static void appendToken(StringBuilder target, String field, Object value) {
		target.append(field).append('=');
		if (value == null) {
			target.append("<null>\n");
			return;
		}
		var text = stableScalar(value);
		target.append(text.length()).append(':').append(text).append('\n');
	}

	private static String stableScalar(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal.stripTrailingZeros().toPlainString();
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
		}
		if (value instanceof UUID || value instanceof String || value instanceof Boolean
				|| value instanceof Character) {
			return value.toString();
		}
		throw new IllegalArgumentException("Unsupported candidate digest scalar: " + value.getClass().getName());
	}

	private static void appendCanonicalJson(StringBuilder target, JsonNode node) {
		if (node == null || node.isNull()) {
			target.append("null");
		}
		else if (node.isObject()) {
			var names = new ArrayList<String>();
			node.propertyNames().forEach(names::add);
			names.sort(String::compareTo);
			target.append("object[").append(names.size()).append("]{");
			for (var name : names) {
				target.append(name.length()).append(':').append(name).append('=');
				appendCanonicalJson(target, node.get(name));
				target.append(';');
			}
			target.append('}');
		}
		else if (node.isArray()) {
			target.append("array[").append(node.size()).append("]{");
			for (var child : node) {
				appendCanonicalJson(target, child);
				target.append(';');
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
			var text = node.asText();
			target.append("string[").append(text.length()).append(':').append(text).append(']');
		}
	}

	private static String assetQuery(List<UUID> ids) {
		return "SELECT clip_asset_id, project_id, sha256 FROM clip_asset WHERE clip_asset_id IN (" + placeholders(ids)
				+ ") ORDER BY clip_asset_id";
	}

	private static String latestJobQuery(List<UUID> ids) {
		return """
				WITH latest_job AS (
				    SELECT DISTINCT ON (job.clip_asset_id)
				           job.ai_job_id, job.project_id, job.clip_asset_id, job.algorithm_key,
				           job.algorithm_version, job.model_sha256, job.input_sha256, job.profile_sha256,
				           job.status, job.hand_result
				      FROM ai_job job
				     WHERE job.clip_asset_id IN (%s)
				       AND job.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
				     ORDER BY job.clip_asset_id, job.created_at DESC, job.ai_job_id DESC
				), latest_attempt AS (
				    SELECT DISTINCT ON (attempt.ai_job_id)
				           attempt.ai_job_id, attempt.ai_job_attempt_id, attempt.attempt, attempt.message_id,
				           attempt.status AS attempt_status, attempt.hand_result AS attempt_hand_result,
				           attempt.analysis_complete, attempt.started_at, attempt.completed_at,
				           attempt.total_video_frames, attempt.inference_frame_count, attempt.frames_with_hand,
				           attempt.frames_without_hand, attempt.left_hand_detection_count,
				           attempt.right_hand_detection_count, attempt.invalid_landmark_count,
				           attempt.processing_duration_ms, attempt.failure_reason, attempt.retryable
				      FROM ai_job_attempt attempt
				     ORDER BY attempt.ai_job_id, attempt.attempt DESC, attempt.created_at DESC,
				              attempt.ai_job_attempt_id DESC
				)
				SELECT job.ai_job_id, job.project_id, job.clip_asset_id, job.algorithm_key,
				       job.algorithm_version, job.model_sha256, job.input_sha256, job.profile_sha256,
				       job.status, job.hand_result, attempt.ai_job_attempt_id, attempt.attempt,
				       attempt.message_id, attempt.attempt_status, attempt.attempt_hand_result,
				       attempt.analysis_complete, attempt.started_at, attempt.completed_at,
				       attempt.total_video_frames, attempt.inference_frame_count, attempt.frames_with_hand,
				       attempt.frames_without_hand, attempt.left_hand_detection_count,
				       attempt.right_hand_detection_count, attempt.invalid_landmark_count,
				       attempt.processing_duration_ms, attempt.failure_reason, attempt.retryable,
				       inbox.result_sha256, aggregate.first_timestamp_ms,
				       aggregate.last_timestamp_ms
				  FROM latest_job job
				  LEFT JOIN latest_attempt attempt ON attempt.ai_job_id = job.ai_job_id
				  LEFT JOIN ai_job_result_inbox inbox
				    ON inbox.ai_job_id = job.ai_job_id
				   AND inbox.attempt = attempt.attempt
				   AND inbox.message_id = attempt.message_id
				  LEFT JOIN (
				      SELECT ai_job_id, MIN(frame_timestamp_ms) AS first_timestamp_ms,
				             MAX(frame_timestamp_ms) AS last_timestamp_ms
				        FROM candidate_annotation
				       GROUP BY ai_job_id
				  ) aggregate ON aggregate.ai_job_id = job.ai_job_id
				 ORDER BY job.clip_asset_id
				""".formatted(placeholders(ids));
	}

	private static String candidateQuery(List<UUID> jobIds) {
		return """
				SELECT candidate_annotation_id, ai_job_id, project_id, clip_asset_id, frame_timestamp_ms,
				       image_width, image_height, hand_index, handedness, handedness_score,
				       bounding_box, landmarks, valid_landmark_count
				  FROM candidate_annotation
				 WHERE ai_job_id IN (%s)
				 ORDER BY ai_job_id, frame_timestamp_ms, hand_index, candidate_annotation_id
				""".formatted(placeholders(jobIds));
	}

	private static String placeholders(Collection<?> values) {
		return values.stream().map(ignored -> "?").collect(Collectors.joining(", "));
	}

	private record ClipAssetFact(UUID clipAssetId, UUID projectId, String sha256) {
	}

	private record JobFact(UUID aiJobId, UUID projectId, UUID clipAssetId, String algorithmKey, String algorithmVersion,
			String modelSha256, String inputSha256, String profileSha256, String status, String handResult,
			UUID attemptId, Integer attempt, UUID messageId, String attemptStatus, String attemptHandResult,
			Boolean analysisComplete, Instant startedAt, Instant completedAt, Long totalVideoFrames,
			Long inferenceFrameCount, Long framesWithHand, Long framesWithoutHand, Long leftHandDetectionCount,
			Long rightHandDetectionCount, Long invalidLandmarkCount, Long processingDurationMs, String failureReason,
			Boolean retryable, String resultSha256, Long firstTimestampMs, Long lastTimestampMs) {
	}

	private record CandidateAnnotationFact(UUID candidateAnnotationId, UUID aiJobId, UUID projectId, UUID clipAssetId,
			long frameTimestampMs, int imageWidth, int imageHeight, int handIndex, String handedness,
			BigDecimal handednessScore, JsonNode boundingBox, JsonNode landmarks, int validLandmarkCount) {

		CandidateAnnotationSnapshot snapshot() {
			return new CandidateAnnotationSnapshot(candidateAnnotationId, aiJobId, projectId, clipAssetId,
					frameTimestampMs, imageWidth, imageHeight, handIndex, handedness, handednessScore, boundingBox,
					landmarks, validLandmarkCount);
		}
	}

	static record CandidateAnnotationSnapshot(UUID candidateAnnotationId, UUID aiJobId, UUID projectId,
			UUID clipAssetId, long frameTimestampMs, int imageWidth, int imageHeight, int handIndex, String handedness,
			BigDecimal handednessScore, JsonNode boundingBox, JsonNode landmarks, int validLandmarkCount) {
	}

	public record CandidateEvidence(UUID clipAssetId, String clipAssetSha256, boolean provenanceValid,
			String executionStatus, String provenanceFailureReason, UUID aiJobId, String inputSha256,
			String algorithmKey, String algorithmVersion, String modelSha256, String profileSha256, UUID aiJobAttemptId,
			Integer attempt, UUID messageId, String resultSha256, String handResult, long candidateCount,
			Long firstTimestampMs, Long lastTimestampMs, String candidateSetDigest, RunSummary runSummary) {

		public CandidateEvidence(UUID clipAssetId, String clipAssetSha256, boolean provenanceValid,
				String executionStatus, String provenanceFailureReason, UUID aiJobId, String inputSha256,
				String algorithmKey, String algorithmVersion, String modelSha256, String profileSha256,
				UUID aiJobAttemptId, Integer attempt, UUID messageId, String resultSha256, String handResult,
				long candidateCount, Long firstTimestampMs, Long lastTimestampMs, String candidateSetDigest) {
			this(clipAssetId, clipAssetSha256, provenanceValid, executionStatus, provenanceFailureReason, aiJobId,
					inputSha256, algorithmKey, algorithmVersion, modelSha256, profileSha256, aiJobAttemptId, attempt,
					messageId, resultSha256, handResult, candidateCount, firstTimestampMs, lastTimestampMs,
					candidateSetDigest, null);
		}
	}

	public record RunSummary(Boolean analysisComplete, String handResult, Long totalVideoFrames,
			Long inferenceFrameCount, Long framesWithHand, Long framesWithoutHand, Long leftHandDetectionCount,
			Long rightHandDetectionCount, Long invalidLandmarkCount, Long processingDurationMs, Instant startedAt,
			Instant completedAt, String failureReason, Boolean retryable) {
	}

}
