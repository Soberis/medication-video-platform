package com.medicationvideo.platform.aicandidate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.assetcapture.ClipAssetInputSummary;
import com.medicationvideo.platform.assetcapture.ClipAssetStatus;
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

	private final AiCandidateJobAttemptQueueService attemptQueue;

	private final org.springframework.context.ApplicationEventPublisher eventPublisher;

	private final int maxAttempts;

	public ActionCandidateJobService(AiCandidateJobRepository jobRepository,
			AiCandidateJobAttemptRepository attemptRepository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			ProjectAccessService projectAccessService, AssetCatalogQueryService assetCatalog,
			AiCandidateJobIdempotencyReceiptRepository receiptRepository, AuditEventRecorder auditRecorder,
			AiJobDispatchPort dispatchPort, AiLeaseCapabilityFactory capabilityFactory,
			ActionJobEnvelopeFactory actionEnvelopeFactory, AiCandidateJobAttemptQueueService attemptQueue,
			int maxAttempts) {
		this(jobRepository, attemptRepository, jdbcTemplate, objectMapper, projectAccessService, assetCatalog,
				receiptRepository, auditRecorder, dispatchPort, capabilityFactory, actionEnvelopeFactory, attemptQueue,
				null, maxAttempts);
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
			@Autowired(required = false) ActionJobEnvelopeFactory actionEnvelopeFactory,
			@Autowired(required = false) AiCandidateJobAttemptQueueService attemptQueue,
			@Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher,
			@Value("${platform.ai.hand-pose.max-attempts:3}") int maxAttempts) {
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
		this.attemptQueue = attemptQueue;
		this.eventPublisher = eventPublisher;
		HandPoseRetryPolicy.validateMaxAttempts(maxAttempts);
		this.maxAttempts = maxAttempts;
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
		requireAcceptableSegments(job, request.segments(), request.durationMs());
		job.recordActionExecutionMetadata(actionExecutionMetadata(request, request.segments().size()));

		for (var seg : request.segments()) {
			String stitchedJson = "[]";
			try {
				if (seg.stitchedWindows() != null && !seg.stitchedWindows().isEmpty()) {
					stitchedJson = this.objectMapper.writeValueAsString(seg.stitchedWindows());
				}
			}
			catch (Exception ignored) {
			}

			this.jdbcTemplate.update(
					"""
							INSERT INTO action_candidate (
							    action_candidate_id, ai_job_id, project_id, capture_group_id, clip_asset_id,
							    annotation_task_id, label_schema_id, label_schema_digest,
							    start_ms, end_ms, action_label, target_object, confidence, source_window, stitched_windows, created_at)
							VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
							""",
					UUID.randomUUID(), job.getAiJobId(), job.getProjectId(), captureGroupId, job.getClipAssetId(),
					job.getAnnotationTaskId(), job.getLabelSchemaId(), job.getLabelSchemaDigest(), seg.startMs(),
					seg.endMs(), seg.label(), seg.targetObject(), seg.confidence(),
					seg.sourceWindow() != null ? seg.sourceWindow() : 1, stitchedJson, receivedAt);
		}

		this.jdbcTemplate.update(
				"INSERT INTO ai_job_result_inbox (message_id, ai_job_id, attempt, result_sha256, received_at) VALUES (?, ?, ?, ?, ?)",
				request.messageId(), jobId, attemptNumber, digest, receivedAt);

		attempt.succeed(now);
		job.succeed(now, HandPoseResult.UNKNOWN);
		LOGGER.info("Action candidate job {} succeeded with {} segments", jobId, request.segments().size());
		if (this.eventPublisher != null) {
			this.eventPublisher.publishEvent(
					new ActionCandidateJobCompletedEvent(job.getProjectId(), captureGroupId, job.getClipAssetId()));
		}
	}

	@Transactional(readOnly = true)
	public List<ActionCandidateResponse> listByCaptureGroup(UUID projectId, UUID captureGroupId) {
		return listByCaptureGroup(projectId, captureGroupId,
				this.actionEnvelopeFactory.resolveTaskPrimaryClipAssetId(projectId, captureGroupId));
	}

	@Transactional(readOnly = true)
	public List<ActionCandidateResponse> listByCaptureGroup(UUID projectId, UUID captureGroupId, UUID clipAssetId) {
		if (clipAssetId == null) {
			return List.of();
		}
		var sql = """
				SELECT c.action_candidate_id, c.ai_job_id, c.project_id, c.capture_group_id, c.clip_asset_id,
				       c.annotation_task_id, c.label_schema_id, c.label_schema_digest,
				       start_ms, end_ms, action_label, target_object, confidence, source_window, stitched_windows, created_at
				  FROM action_candidate c
				 WHERE c.project_id = ? AND c.capture_group_id = ? AND c.clip_asset_id = ?
				   AND c.ai_job_id = (SELECT j.ai_job_id FROM ai_job j
				                       WHERE j.project_id = c.project_id AND j.clip_asset_id = c.clip_asset_id
				                         AND j.algorithm_key = 'QWEN3_5_ACTION'
				                       ORDER BY j.created_at DESC, j.ai_job_id DESC LIMIT 1)
				   AND EXISTS (SELECT 1 FROM ai_job j WHERE j.ai_job_id = c.ai_job_id AND j.status = 'SUCCEEDED')
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

			var confidence = rs.getBigDecimal("confidence");
			return new ActionCandidateResponse(rs.getObject("action_candidate_id", UUID.class),
					rs.getObject("ai_job_id", UUID.class), rs.getObject("project_id", UUID.class),
					rs.getObject("capture_group_id", UUID.class), rs.getObject("clip_asset_id", UUID.class),
					rs.getObject("annotation_task_id", UUID.class), rs.getObject("label_schema_id", UUID.class),
					rs.getString("label_schema_digest"), rs.getLong("start_ms"), rs.getLong("end_ms"),
					rs.getString("action_label"), confidence == null ? null : confidence.doubleValue(),
					rs.getInt("source_window"), stitched, rs.getString("target_object"),
					rs.getTimestamp("created_at").toInstant());
		}, projectId, captureGroupId, clipAssetId);
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
		return AiDigests.sha256(this.objectMapper.writeValueAsString(request));
	}

	/**
	 * Rejects candidate sets the platform cannot store faithfully: unbounded results,
	 * labels outside the active schema, intervals beyond the reported duration, and
	 * overlapping intervals (the product rule allows blank gaps instead of overlaps).
	 */
	private void requireAcceptableSegments(AiCandidateJob job, List<ActionCandidateSegmentItem> segments,
			Long durationMs) {
		if (segments.size() > MAX_RESULT_SEGMENTS) {
			throw new HandPoseJobException("AI_ACTION_RESULT_TOO_LARGE",
					"An action result may not contain more than " + MAX_RESULT_SEGMENTS + " segments.", 400);
		}
		var allowedLabels = job.getAllowedLabels().isEmpty() ? this.actionEnvelopeFactory.resolveAllowedLabels()
				: job.getAllowedLabels();
		for (var segment : segments) {
			if (segment == null || segment.startMs() == null || segment.endMs() == null) {
				throw new HandPoseJobException("INVALID_SEGMENT_INTERVAL", "Action segment bounds are required.", 400);
			}
		}
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

	private static Map<String, Object> actionExecutionMetadata(ActionCandidateResultRequest request, int segmentCount) {
		var metadata = new LinkedHashMap<String, Object>();
		metadata.put("model_name", request.modelName());
		if (request.promptVersion() != null && !request.promptVersion().isBlank()) {
			metadata.put("prompt_version", request.promptVersion());
		}
		metadata.put("total_windows", request.totalWindows());
		metadata.put("duration_ms", request.durationMs());
		metadata.put("inference_duration_ms", request.inferenceDurationMs());
		metadata.put("started_at", request.startedAt().toString());
		metadata.put("completed_at", request.completedAt().toString());
		metadata.put("segment_count", segmentCount);
		return metadata;
	}

	@Transactional
	public ActionCandidateJobResponse queueActionCandidateJob(UUID projectId, UUID captureGroupId, String actorId,
			String idempotencyKey, String requestId, String traceId) {
		if (this.projectAccessService != null) {
			this.projectAccessService.lockAndRequireMember(projectId, actorId);
		}
		return queueActionCandidateJobInternal(projectId, captureGroupId, null, actorId, idempotencyKey, requestId,
				traceId, AuditActorType.USER, "ROLE_MEMBER", "AI_ACTION_CANDIDATE_JOB_QUEUED");
	}

	@Transactional
	public void queueAutomatically(UUID projectId, UUID clipAssetId) {
		this.projectAccessService.lockActiveProjectForSystemProcessing(projectId);
		var input = requireEligibleInput(projectId, clipAssetId);
		var captureGroupId = resolveCaptureGroupId(clipAssetId);
		if (captureGroupId == null)
			return;
		// Automatic reconciliation never spends another attempt on a terminal failure.
		if (this.jobRepository.findAllByProjectIdAndClipAssetIdOrderByCreatedAtDesc(projectId, clipAssetId)
			.stream()
			.anyMatch(job -> AiCandidateJob.ACTION_ALGORITHM_KEY.equals(job.getAlgorithmKey())
					&& input.inputDigest().equals(job.getInputSha256())))
			return;
		queueActionCandidateJobInternal(projectId, captureGroupId, clipAssetId, "AUTO_ACTION_PREPARATION",
				"auto-action-" + clipAssetId, "auto-action-" + captureGroupId, null, AuditActorType.SYSTEM,
				"MEDIA_INGESTED", "AI_ACTION_CANDIDATE_JOB_AUTO_QUEUED");
	}

	private ActionCandidateJobResponse queueActionCandidateJobInternal(UUID projectId, UUID captureGroupId,
			UUID requestedClipAssetId, String actorId, String idempotencyKey, String requestId, String traceId,
			AuditActorType auditActorType, String authorizationReference, String auditAction) {
		var labelSnapshot = this.actionEnvelopeFactory.resolveLabelSnapshot(projectId, captureGroupId);
		var clipAssetId = requestedClipAssetId != null ? requestedClipAssetId
				: this.actionEnvelopeFactory.resolveTaskPrimaryClipAssetId(projectId, captureGroupId);
		if (clipAssetId == null)
			clipAssetId = labelSnapshot.primaryClipAssetId();
		if (clipAssetId == null) {
			throw new HandPoseJobException("AI_ACTION_SOURCE_INVALID",
					"No primary video stream found for capture group " + captureGroupId, 404);
		}

		var input = requireEligibleInput(projectId, clipAssetId);
		var inputDigest = input.inputDigest();
		long inputSize = input.inputSizeBytes();
		var contentType = input.contentType();

		var now = Instant.now();
		var fingerprint = AiDigests.sha256(projectId + ":" + clipAssetId + ":" + idempotencyKey);
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
			if (AiCandidateJob.ACTION_ALGORITHM_KEY.equals(candidate.getAlgorithmKey())
					&& inputDigest.equals(candidate.getInputSha256())
					&& java.util.Objects.equals(labelSnapshot.schemaDigest(), candidate.getLabelSchemaDigest())) {
				if (candidate.getStatus() == AiCandidateJobStatus.QUEUED
						|| candidate.getStatus() == AiCandidateJobStatus.RUNNING
						|| candidate.getStatus() == AiCandidateJobStatus.SUCCEEDED) {
					return response(candidate, captureGroupId);
				}
				if (candidate.getStatus() == AiCandidateJobStatus.FAILED) {
					return retryFailedActionJob(projectId, captureGroupId, actorId, idempotencyKey, requestId, traceId,
							candidate, fingerprint, receiptKey, auditActorType, authorizationReference);
				}
			}
		}

		var job = this.jobRepository
			.save(AiCandidateJob.queueAction(projectId, clipAssetId, AiCandidateJob.ACTION_MODEL_SHA256, inputDigest,
					inputSize, contentType, idempotencyKey, fingerprint, actorId, now, labelSnapshot));

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
			this.attemptRepository
				.save(AiCandidateJobAttempt.queue(job.getAiJobId(), 1, messageId, AiDigests.sha256(leaseToken), now));
		}

		if (this.dispatchPort != null) {
			this.dispatchPort.enqueueAction(
					this.actionEnvelopeFactory.create(job, captureGroupId, 1, messageId, leaseToken, now), now);
		}

		if (this.auditRecorder != null) {
			var resolvedRequestId = (requestId != null && !requestId.isBlank()) ? requestId
					: UUID.randomUUID().toString();
			var resolvedTraceId = (traceId != null && !traceId.isBlank()) ? traceId : resolvedRequestId;
			this.auditRecorder.record(new AuditEventCommand(now, auditActorType, actorId, projectId, auditAction,
					"AIJob", job.getAiJobId().toString(), "1", AuditOutcome.SUCCEEDED,
					AiCandidateJob.ACTION_ALGORITHM_KEY, resolvedRequestId, resolvedTraceId, authorizationReference,
					Map.of("capture_group_id", captureGroupId.toString(), "clip_asset_id", clipAssetId.toString(),
							"algorithm_key", AiCandidateJob.ACTION_ALGORITHM_KEY)));
		}

		return response(job, captureGroupId);
	}

	private ActionCandidateJobResponse retryFailedActionJob(UUID projectId, UUID captureGroupId, String actorId,
			String idempotencyKey, String requestId, String traceId, AiCandidateJob candidate, String fingerprint,
			String receiptKey, AuditActorType auditActorType, String authorizationReference) {
		if (this.attemptQueue == null) {
			throw new HandPoseJobException("AI_ACTION_RETRY_UNAVAILABLE",
					"The action candidate retry service is not configured.", 503);
		}
		var job = this.jobRepository.findForUpdate(candidate.getAiJobId()).orElse(candidate);
		var attempt = this.attemptQueue.latest(job.getAiJobId());
		if (attempt == null || attempt.getStatus() != AiCandidateJobAttemptStatus.FAILED) {
			throw new HandPoseJobException("AI_ACTION_RETRY_STATE_CONFLICT",
					"Only a failed action candidate attempt can be retried.", 409);
		}

		var now = Instant.now();
		job.requeueForManualRetry(now);
		this.attemptQueue.enqueue(job, attempt.getAttempt() + 1, now);
		if (this.receiptRepository != null) {
			this.receiptRepository.save(
					AiCandidateJobIdempotencyReceipt.record(projectId, receiptKey, fingerprint, job.getAiJobId(), now));
		}
		if (this.auditRecorder != null) {
			this.auditRecorder.record(new AuditEventCommand(now, auditActorType, actorId, projectId,
					"AI_ACTION_CANDIDATE_JOB_MANUAL_RETRY_QUEUED", "AIJob", job.getAiJobId().toString(),
					Integer.toString(attempt.getAttempt() + 1), AuditOutcome.SUCCEEDED,
					attempt.getFailureReason() == null ? "ACTION_CANDIDATE_RETRY" : attempt.getFailureReason(),
					requestId, traceId, authorizationReference,
					Map.of("capture_group_id", captureGroupId.toString(), "clip_asset_id",
							job.getClipAssetId().toString(), "source_attempt",
							Integer.toString(attempt.getAttempt()))));
		}
		return response(job, captureGroupId);
	}

	private ClipAssetInputSummary requireEligibleInput(UUID projectId, UUID clipAssetId) {
		if (this.assetCatalog == null) {
			throw new HandPoseJobException("AI_ACTION_SOURCE_INVALID", "The action candidate source is unavailable.",
					409);
		}
		var input = this.assetCatalog.getClipAssetInputSummary(projectId, clipAssetId);
		if (input == null || input.status() != ClipAssetStatus.INGESTED || input.contentType() == null
				|| !input.contentType().startsWith("video/")) {
			throw new HandPoseJobException("CLIP_ASSET_NOT_READY_FOR_AI_CANDIDATES",
					"Only a media-processed video clip asset can receive action candidates.", 409);
		}
		return input;
	}

	@Transactional(readOnly = true)
	public ActionCandidateJobResponse getJobStatus(UUID projectId, UUID captureGroupId) {
		var clipAssetId = this.actionEnvelopeFactory.resolveTaskPrimaryClipAssetId(projectId, captureGroupId);
		return getJobStatus(projectId, captureGroupId, clipAssetId);
	}

	@Transactional(readOnly = true)
	public ActionCandidateJobResponse getJobStatus(UUID projectId, UUID captureGroupId, UUID clipAssetId) {
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

	private ActionCandidateJobResponse response(AiCandidateJob job, UUID captureGroupId) {
		int attemptNum = 1;
		String failureReason = null;
		var attempt = this.attemptRepository.findFirstByAiJobIdOrderByAttemptDesc(job.getAiJobId());
		if (attempt.isPresent()) {
			attemptNum = attempt.get().getAttempt();
			failureReason = attempt.get().getFailureReason();
		}
		return new ActionCandidateJobResponse(job.getAiJobId(), job.getProjectId(), captureGroupId,
				job.getClipAssetId(), job.getStatus().name(), attemptNum, failureReason, job.getCreatedAt(),
				job.getUpdatedAt());
	}

}
