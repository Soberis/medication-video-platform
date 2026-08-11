package com.medicationvideo.platform.aicandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
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

	private static final Duration DELIVERY_RECOVERY_DELAY = Duration.ofMinutes(5);

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
		if (maxAttempts < 1 || maxAttempts > 10) {
			throw new IllegalArgumentException("Hand pose max attempts must be between 1 and 10.");
		}
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

	/**
	 * Compatibility constructor for narrow unit tests; the old role value is
	 * intentionally ignored.
	 */
	HandPoseJobService(ProjectAccessService projectAccessService, AssetCatalogQueryService assetCatalog,
			AiCandidateJobRepository repository, AiCandidateJobIdempotencyReceiptRepository receiptRepository,
			AuditEventRecorder auditRecorder, AiCandidateJobAttemptQueueService attemptQueue, String modelSha256,
			String ignoredApprovedCameraRoles, boolean enabled) {
		this(projectAccessService, assetCatalog, repository, receiptRepository, auditRecorder, attemptQueue,
				(MediaDurationQueryService) null, modelSha256, enabled, 3);
	}

	@Transactional
	HandPoseJobResponse queue(UUID projectId, UUID clipAssetId, String actorId, String idempotencyKey, String requestId,
			String traceId) {
		ensureEnabled();
		var projectRole = this.projectAccessService.lockAndRequireMember(projectId, actorId);
		validateModel();
		var fingerprint = fingerprint(projectId, clipAssetId);
		var receipt = this.receiptRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (receipt.isPresent()) {
			return response(requireSameRequest(receipt.get(), fingerprint));
		}
		var legacyReplay = this.repository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (legacyReplay.isPresent()) {
			var job = requireSameRequest(legacyReplay.get(), fingerprint);
			this.recordReceipt(job, idempotencyKey, fingerprint, Instant.now().truncatedTo(ChronoUnit.MICROS));
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
			requeueStalledDelivery(job, now, actorId, projectRole.name(), requestId, traceId);
			this.recordReceipt(job, idempotencyKey, fingerprint, Instant.now().truncatedTo(ChronoUnit.MICROS));
			return response(job);
		}
		var job = this.repository
			.save(AiCandidateJob.queue(projectId, clipAssetId, this.modelSha256, input.inputDigest(),
					input.inputSizeBytes(), input.contentType(), idempotencyKey, fingerprint, actorId, now));
		this.recordReceipt(job, idempotencyKey, fingerprint, now);
		this.attemptQueue.enqueue(job, 1, now);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"AI_HAND_POSE_JOB_QUEUED", "AIJob", job.getAiJobId().toString(), "1", AuditOutcome.SUCCEEDED,
				"MEDIAPIPE_HAND_LANDMARKER", requestId, traceId, "ROLE_" + projectRole.name(),
				java.util.Map.of("clip_asset_id", clipAssetId.toString(), "model_sha256", this.modelSha256,
						"sampling_fps", Integer.toString(HandPoseProfile.SAMPLING_FPS))));
		return response(job);
	}

	@Transactional
	HandPoseJobResponse retry(UUID projectId, UUID clipAssetId, String actorId, String idempotencyKey, String requestId,
			String traceId) {
		ensureEnabled();
		var projectRole = this.projectAccessService.lockAndRequireMember(projectId, actorId);
		validateModel();
		var fingerprint = fingerprint(projectId, clipAssetId);
		var receipt = this.receiptRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (receipt.isPresent()) {
			return response(requireSameRequest(receipt.get(), fingerprint));
		}
		var input = this.assetCatalog.getClipAssetInputSummary(projectId, clipAssetId);
		ensureEligible(input);
		ensureCapacity(input, projectId, clipAssetId);
		var job = this.repository.findTopByProjectIdAndClipAssetIdOrderByCreatedAtDesc(projectId, clipAssetId)
			.orElseThrow(() -> new HandPoseJobException("AI_HAND_POSE_JOB_NOT_FOUND",
					"No hand pose job exists for this video.", 404));
		var attempt = this.attemptQueue.latest(job.getAiJobId());
		if (job.getStatus() != AiCandidateJobStatus.FAILED || attempt == null
				|| attempt.getStatus() != AiCandidateJobAttemptStatus.FAILED) {
			throw new HandPoseJobException("AI_HAND_POSE_RETRY_NOT_ALLOWED",
					"Only a terminally failed hand pose job can be retried.", 409);
		}
		if (!HandPoseRetryPolicy.isRetryable(attempt.getFailureReason())) {
			throw new HandPoseJobException("AI_HAND_POSE_RETRY_NOT_ALLOWED",
					"This hand pose failure is not eligible for manual retry.", 409);
		}
		if (attempt.getAttempt() >= this.maxAttempts) {
			throw new HandPoseJobException("AI_HAND_POSE_RETRY_LIMIT_REACHED",
					"The maximum number of hand pose attempts has been reached.", 409);
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		job.requeueForManualRetry(now);
		this.attemptQueue.enqueue(job, attempt.getAttempt() + 1, now);
		this.recordReceipt(job, idempotencyKey, fingerprint, now);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"AI_HAND_POSE_JOB_MANUAL_RETRY_QUEUED", "AIJob", job.getAiJobId().toString(),
				Integer.toString(attempt.getAttempt() + 1), AuditOutcome.SUCCEEDED, attempt.getFailureReason(),
				requestId, traceId, "ROLE_" + projectRole.name(), java.util.Map.of("clip_asset_id",
						clipAssetId.toString(), "source_attempt", Integer.toString(attempt.getAttempt()))));
		return response(job);
	}

	private void requeueStalledDelivery(AiCandidateJob job, Instant now, String actorId, String projectRole,
			String requestId, String traceId) {
		if (job.getStatus() != AiCandidateJobStatus.QUEUED)
			return;
		var attempt = this.attemptQueue.latest(job.getAiJobId());
		if (attempt == null || attempt.getStatus() != AiCandidateJobAttemptStatus.QUEUED
				|| now.isBefore(attempt.getCreatedAt().plus(DELIVERY_RECOVERY_DELAY)))
			return;
		attempt.markDeliveryMissing(now);
		job.requeueAfterMissingDelivery(now);
		this.attemptQueue.enqueue(job, attempt.getAttempt() + 1, now);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, job.getProjectId(),
				"AI_HAND_POSE_JOB_DELIVERY_REQUEUED", "AIJob", job.getAiJobId().toString(), "1", AuditOutcome.SUCCEEDED,
				"MISSING_BROKER_DELIVERY", requestId, traceId, "ROLE_" + projectRole,
				java.util.Map.of("source_attempt", Integer.toString(attempt.getAttempt()))));
	}

	@Transactional(readOnly = true)
	HandPoseJobResponse get(UUID projectId, UUID aiJobId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var job = this.repository.findById(aiJobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new HandPoseJobException("AI_HAND_POSE_JOB_NOT_FOUND",
					"The hand pose job was not found.", 404));
		return response(job);
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
		return HandPoseJobResponse.from(job, this.attemptQueue.latest(job.getAiJobId()));
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

	private String fingerprint(UUID projectId, UUID clipAssetId) {
		return HandPoseProfile.sha256(String.join("\n", projectId.toString(), clipAssetId.toString(), this.modelSha256,
				HandPoseProfile.PROFILE_SHA256));
	}

}
