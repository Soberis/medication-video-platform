package com.medicationvideo.platform.aicandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@Service
public class ActionCandidateJobService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ActionCandidateJobService.class);

	private final AiCandidateJobRepository jobRepository;

	private final AiCandidateJobAttemptRepository attemptRepository;

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	ActionCandidateJobService(AiCandidateJobRepository jobRepository, AiCandidateJobAttemptRepository attemptRepository,
			JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public void acceptResult(UUID jobId, int attemptNumber, String leaseToken, ActionCandidateResultRequest request,
			String authenticatedWorkerId) {
		var job = lockJob(jobId);
		var attempt = lockAttempt(jobId, attemptNumber);
		var now = Instant.now();
		var digest = computeDigest(request);

		var inboxHits = this.jdbcTemplate.queryForList(
				"SELECT result_sha256 FROM ai_job_result_inbox WHERE message_id = ?", String.class,
				request.messageId());
		if (!inboxHits.isEmpty()) {
			if (digest.equals(inboxHits.getFirst())) {
				LOGGER.info("Idempotent duplicate action result received for message {}", request.messageId());
				return;
			}
			throw new HandPoseJobException("AI_ACTION_RESULT_MESSAGE_REUSED",
					"The result message identifier was reused with different content.", 409);
		}

		requireLease(attempt, leaseToken);
		attempt.requireActiveLease(now);
		if (job.getStatus() != AiCandidateJobStatus.RUNNING) {
			throw new HandPoseJobException("STALE_ATTEMPT", "The AI job is no longer running.", 409);
		}

		// Resolve captureGroupId for the clipAssetId via camera_stream
		UUID captureGroupId = resolveCaptureGroupId(job.getClipAssetId());
		if (captureGroupId == null) {
			captureGroupId = job.getClipAssetId();
		}

		var receivedAt = Timestamp.from(now);

		// Validate segments and insert
		for (var seg : request.segments()) {
			if (seg.startMs() == null || seg.endMs() == null || seg.endMs() <= seg.startMs()) {
				throw new HandPoseJobException("INVALID_SEGMENT_INTERVAL",
						"Action segment interval must satisfy 0 <= startMs < endMs.", 400);
			}
			String stitchedJson = "[]";
			try {
				if (seg.stitchedWindows() != null && !seg.stitchedWindows().isEmpty()) {
					stitchedJson = this.objectMapper.writeValueAsString(seg.stitchedWindows());
				}
			}
			catch (Exception ignored) {
			}

			this.jdbcTemplate.update("""
					INSERT INTO action_candidate (
					    action_candidate_id, ai_job_id, project_id, capture_group_id, clip_asset_id,
					    start_ms, end_ms, action_label, confidence, source_window, stitched_windows, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
					""", UUID.randomUUID(), job.getAiJobId(), job.getProjectId(), captureGroupId, job.getClipAssetId(),
					seg.startMs(), seg.endMs(), seg.label(), seg.confidence(),
					seg.sourceWindow() != null ? seg.sourceWindow() : 1, stitchedJson, receivedAt);
		}

		this.jdbcTemplate.update(
				"INSERT INTO ai_job_result_inbox (message_id, ai_job_id, attempt, result_sha256, received_at) VALUES (?, ?, ?, ?, ?)",
				request.messageId(), jobId, attemptNumber, digest, receivedAt);

		attempt.succeed(now);
		job.succeed(now, HandPoseResult.UNKNOWN);
		LOGGER.info("Action candidate job {} succeeded with {} segments", jobId, request.segments().size());
	}

	@Transactional(readOnly = true)
	public List<ActionCandidateResponse> listByCaptureGroup(UUID projectId, UUID captureGroupId) {
		var sql = """
				SELECT action_candidate_id, ai_job_id, project_id, capture_group_id, clip_asset_id,
				       start_ms, end_ms, action_label, confidence, source_window, stitched_windows, created_at
				  FROM action_candidate
				 WHERE project_id = ? AND capture_group_id = ?
				 ORDER BY start_ms ASC, end_ms ASC
				""";
		return this.jdbcTemplate.query(sql, (rs, rowNum) -> {
			List<Integer> stitched = new ArrayList<>();
			try {
				var raw = rs.getString("stitched_windows");
				if (raw != null && !raw.isBlank()) {
					var node = this.objectMapper.readTree(raw);
					if (node.isArray()) {
						for (var item : node) {
							stitched.add(item.asInt());
						}
					}
				}
			}
			catch (Exception ignored) {
			}

			return new ActionCandidateResponse(rs.getObject("action_candidate_id", UUID.class),
					rs.getObject("ai_job_id", UUID.class), rs.getObject("project_id", UUID.class),
					rs.getObject("capture_group_id", UUID.class), rs.getObject("clip_asset_id", UUID.class),
					rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("action_label"),
					rs.getDouble("confidence"), rs.getInt("source_window"), stitched,
					rs.getTimestamp("created_at").toInstant());
		}, projectId, captureGroupId);
	}

	private UUID resolveCaptureGroupId(UUID clipAssetId) {
		var results = this.jdbcTemplate.queryForList(
				"SELECT capture_session_id FROM camera_stream WHERE clip_asset_id = ?", UUID.class, clipAssetId);
		return results.isEmpty() ? null : results.getFirst();
	}

	private AiCandidateJob lockJob(UUID jobId) {
		return this.jobRepository.findForUpdate(jobId)
			.orElseThrow(() -> new HandPoseJobException("AI_ACTION_JOB_NOT_FOUND",
					"The action candidate job was not found.", 404));
	}

	private AiCandidateJobAttempt lockAttempt(UUID jobId, int attemptNumber) {
		return this.attemptRepository.findByAiJobIdAndAttempt(jobId, attemptNumber)
			.orElseThrow(() -> new HandPoseJobException("STALE_ATTEMPT", "The AI job attempt was not found.", 409));
	}

	private static void requireLease(AiCandidateJobAttempt attempt, String token) {
		if (!attempt.matchesLeaseHash(AiLeaseCapabilityFactory.sha256(token))) {
			throw new HandPoseJobException("AI_ACTION_JOB_LEASE_INVALID", "The AI job lease token is invalid.", 409);
		}
	}

	private static String computeDigest(ActionCandidateResultRequest request) {
		try {
			var md = MessageDigest.getInstance("SHA-256");
			md.update(request.messageId().toString().getBytes(StandardCharsets.UTF_8));
			md.update(request.modelName().getBytes(StandardCharsets.UTF_8));
			for (var seg : request.segments()) {
				md.update(String.valueOf(seg.startMs()).getBytes(StandardCharsets.UTF_8));
				md.update(String.valueOf(seg.endMs()).getBytes(StandardCharsets.UTF_8));
				md.update(seg.label().getBytes(StandardCharsets.UTF_8));
			}
			var bytes = md.digest();
			var sb = new StringBuilder();
			for (byte b : bytes) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (Exception exc) {
			throw new IllegalStateException("SHA-256 unavailable", exc);
		}
	}

}
