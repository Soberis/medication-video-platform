package com.medicationvideo.platform.aicandidate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

import tools.jackson.databind.ObjectMapper;

@Service
public class ActionCandidateJobService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ActionCandidateJobService.class);

	static final int MAX_RESULT_SEGMENTS = 2000;

	private final AiCandidateJobRepository jobRepository;

	private final AiCandidateJobAttemptRepository attemptRepository;

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final ProjectAccessService projectAccessService;

	private final AssetCatalogQueryService assetCatalog;

	private final AiCandidateJobIdempotencyReceiptRepository receiptRepository;

	private final AuditEventRecorder auditRecorder;

	private final AiJobDispatchPort dispatchPort;

	private final AiLeaseCapabilityFactory capabilityFactory;

	private final ActionJobEnvelopeFactory actionEnvelopeFactory;

	ActionCandidateJobService(AiCandidateJobRepository jobRepository, AiCandidateJobAttemptRepository attemptRepository,
			JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this(jobRepository, attemptRepository, jdbcTemplate, objectMapper, null, null, null, null, null, null,
				new ActionJobEnvelopeFactory(null, null));
	}

	@Autowired
	public ActionCandidateJobService(AiCandidateJobRepository jobRepository,
			AiCandidateJobAttemptRepository attemptRepository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			@Autowired(required = false) ProjectAccessService projectAccessService,
			@Autowired(required = false) AssetCatalogQueryService assetCatalog,
			@Autowired(required = false) AiCandidateJobIdempotencyReceiptRepository receiptRepository,
			@Autowired(required = false) AuditEventRecorder auditRecorder,
			@Autowired(required = false) AiJobDispatchPort dispatchPort,
			@Autowired(required = false) AiLeaseCapabilityFactory capabilityFactory,
			@Autowired(required = false) ActionJobEnvelopeFactory actionEnvelopeFactory) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.projectAccessService = projectAccessService;
		this.assetCatalog = assetCatalog;
		this.receiptRepository = receiptRepository;
		this.auditRecorder = auditRecorder;
		this.dispatchPort = dispatchPort;
		this.capabilityFactory = capabilityFactory;
		this.actionEnvelopeFactory = actionEnvelopeFactory != null ? actionEnvelopeFactory
				: new ActionJobEnvelopeFactory(assetCatalog, null);
	}

	@Transactional
	public void acceptResult(UUID jobId, int attemptNumber, String leaseToken, ActionCandidateResultRequest request,
			String authenticatedWorkerId) {
		var job = lockJob(jobId);
		if (!AiCandidateJob.ACTION_ALGORITHM_KEY.equals(job.getAlgorithmKey())) {
			throw new HandPoseJobException("AI_ACTION_JOB_TYPE_MISMATCH",
					"Action results cannot complete a different algorithm's job.", 409);
		}
		var attempt = lockAttempt(jobId, attemptNumber);
		attempt.requireWorker(authenticatedWorkerId);
		requireLease(attempt, leaseToken);
		if (!attempt.getMessageId().equals(request.messageId())) {
			throw new HandPoseJobException("AI_ACTION_RESULT_IDENTITY_MISMATCH",
					"The result does not identify the current AI job attempt.", 409);
		}
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

		attempt.requireActiveLease(now);
		if (job.getStatus() != AiCandidateJobStatus.RUNNING) {
			throw new HandPoseJobException("STALE_ATTEMPT", "The AI job is no longer running.", 409);
		}

		// Resolve captureGroupId for the clipAssetId via camera_stream
		UUID captureGroupId = resolveCaptureGroupId(job.getClipAssetId());
		if (captureGroupId == null) {
			throw new HandPoseJobException("AI_ACTION_SOURCE_INVALID",
					"The source clip does not belong to a capture group.", 409);
		}

		var receivedAt = Timestamp.from(now);
		requireAcceptableSegments(request.segments(), request.durationMs());

		for (var seg : request.segments()) {
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
				   AND ai_job_id IN (SELECT ai_job_id FROM ai_job
				                       WHERE algorithm_key = 'QWEN3_VL_ACTION' AND status = 'SUCCEEDED')
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
		if (this.assetCatalog != null) {
			return this.assetCatalog.resolveCaptureGroupId(clipAssetId);
		}
		return this.actionEnvelopeFactory.resolveCaptureGroupId(clipAssetId);
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

	private String computeDigest(ActionCandidateResultRequest request) {
		return HandPoseProfile.sha256(this.objectMapper.writeValueAsString(request));
	}

	/**
	 * Rejects candidate sets the platform cannot store faithfully: unbounded results,
	 * labels outside the active schema, intervals beyond the reported duration, and
	 * overlapping intervals (the product rule allows blank gaps instead of overlaps).
	 */
	private void requireAcceptableSegments(List<ActionCandidateSegmentItem> segments, Long durationMs) {
		if (segments.size() > MAX_RESULT_SEGMENTS) {
			throw new HandPoseJobException("AI_ACTION_RESULT_TOO_LARGE",
					"An action result may not contain more than " + MAX_RESULT_SEGMENTS + " segments.", 400);
		}
		var allowedLabels = this.actionEnvelopeFactory.resolveAllowedLabels();
		var ordered = new ArrayList<>(segments);
		ordered.sort(Comparator.comparingLong(ActionCandidateSegmentItem::startMs));
		var previousEnd = -1L;
		for (var seg : ordered) {
			if (seg.startMs() == null || seg.endMs() == null || seg.startMs() < 0 || seg.endMs() <= seg.startMs()) {
				throw new HandPoseJobException("INVALID_SEGMENT_INTERVAL",
						"Action segment interval must satisfy 0 <= startMs < endMs.", 400);
			}
			if (durationMs != null && seg.endMs() > durationMs) {
				throw new HandPoseJobException("INVALID_SEGMENT_INTERVAL",
						"Action segment interval must not exceed the reported video duration.", 400);
			}
			if (!allowedLabels.contains(seg.label())) {
				throw new HandPoseJobException("INVALID_SEGMENT_LABEL",
						"Action segment label is not part of the active label schema.", 400);
			}
			if (seg.startMs() < previousEnd) {
				throw new HandPoseJobException("OVERLAPPING_SEGMENT_INTERVAL",
						"Action segments must not overlap; blank intervals are allowed instead.", 400);
			}
			previousEnd = seg.endMs();
		}
	}

	@Transactional
	public ActionCandidateJobResponse queueActionCandidateJob(UUID projectId, UUID captureGroupId, String actorId,
			String idempotencyKey, String requestId, String traceId) {
		if (this.projectAccessService != null) {
			this.projectAccessService.lockAndRequireMember(projectId, actorId);
		}
		var clipAssetId = resolvePrimaryClipAssetId(captureGroupId);
		if (clipAssetId == null) {
			throw new HandPoseJobException("AI_ACTION_SOURCE_INVALID",
					"No primary video stream found for capture group " + captureGroupId, 404);
		}

		var inputDigest = "0000000000000000000000000000000000000000000000000000000000000000";
		long inputSize = 1000L;
		var contentType = "video/mp4";
		if (this.assetCatalog != null) {
			var input = this.assetCatalog.getClipAssetInputSummary(projectId, clipAssetId);
			if (input != null) {
				inputDigest = input.inputDigest();
				inputSize = input.inputSizeBytes();
				contentType = input.contentType();
			}
		}

		var now = Instant.now();
		var fingerprint = HandPoseProfile.sha256(projectId + ":" + clipAssetId + ":" + idempotencyKey);
		var receiptKey = "action-queue:" + idempotencyKey;

		if (this.receiptRepository != null) {
			var existingReceipt = this.receiptRepository.findByProjectIdAndIdempotencyKey(projectId, receiptKey);
			if (existingReceipt.isPresent()) {
				var existingJob = this.jobRepository.findById(existingReceipt.get().getAiJobId());
				if (existingJob.isPresent()) {
					return response(existingJob.get(), captureGroupId);
				}
			}
		}

		var existingList = this.jobRepository.findAllByProjectIdAndClipAssetIdOrderByCreatedAtDesc(projectId,
				clipAssetId);
		for (var candidate : existingList) {
			if (AiCandidateJob.ACTION_ALGORITHM_KEY.equals(candidate.getAlgorithmKey())) {
				if (candidate.getStatus() == AiCandidateJobStatus.QUEUED
						|| candidate.getStatus() == AiCandidateJobStatus.RUNNING
						|| candidate.getStatus() == AiCandidateJobStatus.SUCCEEDED) {
					return response(candidate, captureGroupId);
				}
			}
		}

		var job = this.jobRepository.save(AiCandidateJob.queueAction(projectId, clipAssetId, "qwen3-vl-8b", inputDigest,
				inputSize, contentType, idempotencyKey, fingerprint, actorId, now));

		if (this.receiptRepository != null) {
			this.receiptRepository.save(
					AiCandidateJobIdempotencyReceipt.record(projectId, receiptKey, fingerprint, job.getAiJobId(), now));
		}

		UUID messageId = UUID.randomUUID();
		String leaseToken = UUID.randomUUID().toString().replace("-", "")
				+ UUID.randomUUID().toString().replace("-", "");
		if (this.capabilityFactory != null) {
			var cap = this.capabilityFactory.create();
			leaseToken = cap.token();
			this.attemptRepository
				.save(AiCandidateJobAttempt.queue(job.getAiJobId(), 1, messageId, cap.tokenHash(), now));
		}
		else {
			this.attemptRepository.save(AiCandidateJobAttempt.queue(job.getAiJobId(), 1, messageId,
					HandPoseProfile.sha256(leaseToken), now));
		}

		if (this.dispatchPort != null) {
			this.dispatchPort.enqueueAction(
					this.actionEnvelopeFactory.create(job, captureGroupId, 1, messageId, leaseToken, now), now);
		}

		if (this.auditRecorder != null) {
			this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
					"AI_ACTION_CANDIDATE_JOB_QUEUED", "AIJob", job.getAiJobId().toString(), "1", AuditOutcome.SUCCEEDED,
					AiCandidateJob.ACTION_ALGORITHM_KEY, requestId, traceId, "ROLE_MEMBER",
					Map.of("capture_group_id", captureGroupId.toString(), "clip_asset_id", clipAssetId.toString(),
							"algorithm_key", AiCandidateJob.ACTION_ALGORITHM_KEY)));
		}

		return response(job, captureGroupId);
	}

	@Transactional(readOnly = true)
	public ActionCandidateJobResponse getJobStatus(UUID projectId, UUID captureGroupId) {
		var clipAssetId = resolvePrimaryClipAssetId(captureGroupId);
		if (clipAssetId == null) {
			return null;
		}
		var jobs = this.jobRepository.findAllByProjectIdAndClipAssetIdOrderByCreatedAtDesc(projectId, clipAssetId);
		for (var job : jobs) {
			if (AiCandidateJob.ACTION_ALGORITHM_KEY.equals(job.getAlgorithmKey())) {
				return response(job, captureGroupId);
			}
		}
		return null;
	}

	public UUID resolvePrimaryClipAssetId(UUID captureGroupId) {
		if (this.assetCatalog != null) {
			return this.assetCatalog.resolvePrimaryClipAssetId(captureGroupId);
		}
		return null;
	}

	private ActionCandidateJobResponse response(AiCandidateJob job, UUID captureGroupId) {
		int attemptNum = 1;
		var attempt = this.attemptRepository.findFirstByAiJobIdOrderByAttemptDesc(job.getAiJobId());
		if (attempt.isPresent()) {
			attemptNum = attempt.get().getAttempt();
		}
		return new ActionCandidateJobResponse(job.getAiJobId(), job.getProjectId(), captureGroupId,
				job.getClipAssetId(), job.getStatus().name(), attemptNum, job.getCreatedAt(), job.getUpdatedAt());
	}

}
