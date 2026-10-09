package com.medicationvideo.platform.aicandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;

@Service
class HandPoseJobService {

	private static final String AUTO_AFTER_MEDIA_INGESTED_ACTOR = "AUTO_AFTER_MEDIA_INGESTED";

	private final ProjectAccessService projectAccessService;

	private final AssetCatalogQueryService assetCatalog;

	private final AiCandidateJobRepository repository;

	private final AiCandidateJobIdempotencyReceiptRepository receiptRepository;

	private final AuditEventRecorder auditRecorder;

	private final AiCandidateJobAttemptQueueService attemptQueue;

	private final MediaDurationQueryService durationQueryService;

	private final String modelSha256;

	private final boolean enabled;

	private final int maxAttempts;

	@Autowired
	HandPoseJobService(ProjectAccessService projectAccessService, AssetCatalogQueryService assetCatalog,
			AiCandidateJobRepository repository, AiCandidateJobIdempotencyReceiptRepository receiptRepository,
			AuditEventRecorder auditRecorder, AiCandidateJobAttemptQueueService attemptQueue,
			MediaDurationQueryService durationQueryService,
			@Value("${platform.ai.hand-pose.model-sha256:}") String modelSha256,
			@Value("${platform.ai.hand-pose.enabled:false}") boolean enabled,
			@Value("${platform.ai.hand-pose.max-attempts:3}") int maxAttempts) {
		HandPoseRetryPolicy.validateMaxAttempts(maxAttempts);
		this.projectAccessService = projectAccessService;
		this.assetCatalog = assetCatalog;
		this.repository = repository;
		this.receiptRepository = receiptRepository;
		this.auditRecorder = auditRecorder;
		this.attemptQueue = attemptQueue;
		this.durationQueryService = durationQueryService;
		this.modelSha256 = modelSha256;
		this.enabled = enabled;
		this.maxAttempts = maxAttempts;
	}

	@Transactional
	HandPoseJobResponse queue(UUID projectId, UUID clipAssetId, String actorId, String idempotencyKey, String requestId,
			String traceId) {
		ensureEnabled();
		var projectRole = this.projectAccessService.lockAndRequireMember(projectId, actorId);
		return queueInternal(projectId, clipAssetId, new QueueContext(actorId, idempotencyKey, requestId, traceId,
				AuditActorType.USER, "ROLE_" + projectRole.name(), "AI_HAND_POSE_JOB_QUEUED"));
	}

	/**
	 * System path used after a successful media ingest. It deliberately shares the same
	 * eligibility, capacity, natural-key and idempotency logic as the user path but does
	 * not impersonate a project member.
	 */
	@Transactional
	HandPoseJobResponse queueAfterMediaIngested(UUID projectId, UUID clipAssetId) {
		ensureEnabled();
		var idempotencyKey = "auto-after-media-ingested-" + clipAssetId;
		return queueInternal(projectId, clipAssetId,
				new QueueContext(AUTO_AFTER_MEDIA_INGESTED_ACTOR, idempotencyKey, idempotencyKey, null,
						AuditActorType.SYSTEM, "SYSTEM/AUTO_AFTER_MEDIA_INGESTED", "AI_HAND_POSE_JOB_AUTO_QUEUED"));
	}

	private HandPoseJobResponse queueInternal(UUID projectId, UUID clipAssetId, QueueContext context) {
		validateModel();
		var fingerprint = fingerprint(projectId, clipAssetId);
		var receiptKey = commandKey("queue", context.idempotencyKey());
		var receipt = this.receiptRepository.findByProjectIdAndIdempotencyKey(projectId, receiptKey);
		if (receipt.isEmpty()) {
			// Receipts written before command namespaces were introduced remain
			// replayable, but all new receipts are isolated by command type.
			receipt = this.receiptRepository.findByProjectIdAndIdempotencyKey(projectId, context.idempotencyKey());
		}
		if (receipt.isPresent()) {
			return response(requireSameRequest(receipt.get(), fingerprint));
		}
		var legacyReplay = this.repository.findByProjectIdAndIdempotencyKey(projectId, context.idempotencyKey());
		if (legacyReplay.isPresent()) {
			var job = requireSameRequest(legacyReplay.get(), fingerprint);
			this.recordReceipt(job, receiptKey, fingerprint, Instant.now().truncatedTo(ChronoUnit.MICROS));
			return response(job);
		}
		var input = this.assetCatalog.getClipAssetInputSummary(projectId, clipAssetId);
		ensureEligible(input);
		ensureCapacity(input, projectId, clipAssetId);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var natural = this.repository
			.findByProjectIdAndClipAssetIdAndInputSha256AndAlgorithmKeyAndAlgorithmVersionAndModelSha256AndSamplingFpsAndProfileSha256(
					projectId, clipAssetId, input.inputDigest(), HandPoseProfile.ALGORITHM_KEY,
					HandPoseProfile.ALGORITHM_VERSION, this.modelSha256, HandPoseProfile.SAMPLING_FPS,
					HandPoseProfile.PROFILE_SHA256);
		if (natural.isPresent()) {
			var job = requireSameRequest(natural.get(), fingerprint);
			if (job.getStatus() == AiCandidateJobStatus.FAILED) {
				return retryFailedJob(projectId, clipAssetId, job, context, receiptKey, fingerprint, now);
			}
			this.recordReceipt(job, receiptKey, fingerprint, Instant.now().truncatedTo(ChronoUnit.MICROS));
			return response(job);
		}
		var job = this.repository.save(AiCandidateJob.queue(projectId, clipAssetId, this.modelSha256,
				input.inputDigest(), input.inputSizeBytes(), input.contentType(), context.idempotencyKey(), fingerprint,
				context.actorId(), now));
		this.recordReceipt(job, receiptKey, fingerprint, now);
		this.attemptQueue.enqueue(job, 1, now);
		this.auditRecorder.record(new AuditEventCommand(now, context.actorType(), context.actorId(), projectId,
				context.auditAction(), "AIJob", job.getAiJobId().toString(), "1", AuditOutcome.SUCCEEDED,
				"MEDIAPIPE_HAND_LANDMARKER", context.requestId(), context.traceId(), context.authorizationReference(),
				java.util.Map.of("clip_asset_id", clipAssetId.toString(), "model_sha256", this.modelSha256,
						"sampling_fps", Integer.toString(HandPoseProfile.SAMPLING_FPS))));
		return response(job);
	}

	private HandPoseJobResponse retryFailedJob(UUID projectId, UUID clipAssetId, AiCandidateJob candidate,
			QueueContext context, String receiptKey, String fingerprint, Instant now) {
		var job = this.repository.findForUpdate(candidate.getAiJobId()).orElse(candidate);
		job.requeueForManualRetry(now);
		this.repository.save(job);
		var attempt = this.attemptQueue.latest(job.getAiJobId());
		int nextAttemptNumber = attempt != null ? attempt.getAttempt() + 1 : 1;
		this.attemptQueue.enqueue(job, nextAttemptNumber, now);
		this.recordReceipt(job, receiptKey, fingerprint, now);
		this.auditRecorder.record(new AuditEventCommand(now, context.actorType(), context.actorId(), projectId,
				"AI_HAND_POSE_JOB_MANUAL_RETRY_QUEUED", "AIJob", job.getAiJobId().toString(),
				Integer.toString(nextAttemptNumber), AuditOutcome.SUCCEEDED, "MEDIAPIPE_HAND_LANDMARKER",
				context.requestId(), context.traceId(), context.authorizationReference(),
				java.util.Map.of("clip_asset_id", clipAssetId.toString(), "model_sha256", this.modelSha256,
						"sampling_fps", Integer.toString(HandPoseProfile.SAMPLING_FPS))));
		return response(job);
	}

	@Transactional(readOnly = true)
	HandPoseJobResponse get(UUID projectId, UUID aiJobId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var job = this.repository.findById(aiJobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new HandPoseJobException("AI_HAND_POSE_JOB_NOT_FOUND",
					"The hand pose job was not found.", 404));
		return HandPoseJobResponse.from(job, this.attemptQueue.latestReadOnly(job.getAiJobId()), this.maxAttempts);
	}

	private void validateModel() {
		if (!this.modelSha256.matches("^[a-f0-9]{64}$")) {
			throw new HandPoseJobException("AI_HAND_POSE_MODEL_NOT_CONFIGURED",
					"An approved offline Hand Landmarker model is not configured.", 409);
		}
	}

	private void ensureEnabled() {
		if (!this.enabled) {
			throw new HandPoseJobException("AI_HAND_POSE_FEATURE_NOT_ENABLED",
					"Hand pose candidate analysis is not enabled for this platform.", 409);
		}
	}

	private void ensureCapacity(ClipAssetInputSummary input, UUID projectId, UUID clipAssetId) {
		if (this.durationQueryService == null) {
			return;
		}
		var durationFact = this.durationQueryService.findLatestVerifiedDurationFact(projectId, clipAssetId)
			.filter(fact -> fact.inputDigest().equals(input.inputDigest()))
			.orElseThrow(() -> new HandPoseJobException("AI_HAND_POSE_MEDIA_SUMMARY_REQUIRED",
					"A verified media summary is required before hand detection can start.", 409));
		HandPoseCapacityGuard.requireWithinP0ResultLimit(durationFact.durationMicroseconds(),
				HandPoseProfile.SAMPLING_FPS, HandPoseProfile.MAX_HANDS);
	}

	private static void ensureEligible(ClipAssetInputSummary input) {
		if (input.status() != ClipAssetStatus.INGESTED || !input.contentType().startsWith("video/")) {
			throw new HandPoseJobException("CLIP_ASSET_NOT_READY_FOR_AI_CANDIDATES",
					"Only a media-processed video clip asset can receive hand pose candidates.", 409);
		}
		if (!input.enabledForHandDetection()) {
			throw new HandPoseJobException("CLIP_ASSET_CAMERA_HAND_DETECTION_DISABLED",
					"Hand detection is disabled for the camera that captured this video.", 409);
		}
	}

	private HandPoseJobResponse response(AiCandidateJob job) {
		return HandPoseJobResponse.from(job, this.attemptQueue.latest(job.getAiJobId()), this.maxAttempts);
	}

	private AiCandidateJob requireSameRequest(AiCandidateJobIdempotencyReceipt receipt, String fingerprint) {
		if (!MessageDigest.isEqual(receipt.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new HandPoseJobException("AI_HAND_POSE_IDEMPOTENCY_KEY_REUSED",
					"The idempotency key was already used for a different hand pose job.", 409);
		}
		return this.repository.findById(receipt.getAiJobId())
			.orElseThrow(() -> new IllegalStateException("An AI job idempotency receipt references no AI job."));
	}

	private static AiCandidateJob requireSameRequest(AiCandidateJob job, String fingerprint) {
		if (!MessageDigest.isEqual(job.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new HandPoseJobException("AI_HAND_POSE_IDEMPOTENCY_KEY_REUSED",
					"The idempotency key was already used for a different hand pose job.", 409);
		}
		return job;
	}

	private void recordReceipt(AiCandidateJob job, String idempotencyKey, String fingerprint, Instant now) {
		this.receiptRepository.save(AiCandidateJobIdempotencyReceipt.record(job.getProjectId(), idempotencyKey,
				fingerprint, job.getAiJobId(), now));
	}

	private static String commandKey(String command, String idempotencyKey) {
		var prefix = command + ":";
		var raw = idempotencyKey == null ? "" : idempotencyKey;
		if (prefix.length() + raw.length() <= 128) {
			return prefix + raw;
		}
		// Keep the DB key bounded without silently colliding queue and retry
		// namespaces when clients use the maximum-length idempotency key.
		return prefix + AiDigests.sha256(raw);
	}

	private String fingerprint(UUID projectId, UUID clipAssetId) {
		return AiDigests.sha256(String.join("\n", projectId.toString(), clipAssetId.toString(), this.modelSha256,
				HandPoseProfile.PROFILE_SHA256));
	}

	private record QueueContext(String actorId, String idempotencyKey, String requestId, String traceId,
			AuditActorType actorType, String authorizationReference, String auditAction) {
	}

}
