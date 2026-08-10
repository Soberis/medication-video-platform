package com.medicationvideo.platform.aicandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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

@Service
class HandPoseJobService {

	private final ProjectAccessService projectAccessService;

	private final AssetCatalogQueryService assetCatalog;

	private final AiCandidateJobRepository repository;

	private final AuditEventRecorder auditRecorder;

	private final AiCandidateJobAttemptQueueService attemptQueue;

	private final String modelSha256;

	HandPoseJobService(ProjectAccessService projectAccessService, AssetCatalogQueryService assetCatalog,
			AiCandidateJobRepository repository, AuditEventRecorder auditRecorder,
			AiCandidateJobAttemptQueueService attemptQueue,
			@Value("${platform.ai.hand-pose.model-sha256:}") String modelSha256) {
		this.projectAccessService = projectAccessService;
		this.assetCatalog = assetCatalog;
		this.repository = repository;
		this.auditRecorder = auditRecorder;
		this.attemptQueue = attemptQueue;
		this.modelSha256 = modelSha256;
	}

	@Transactional
	HandPoseJobResponse queue(UUID projectId, UUID clipAssetId, String actorId, String idempotencyKey, String requestId,
			String traceId) {
		var projectRole = this.projectAccessService.lockAndRequireMember(projectId, actorId);
		validateModel();
		var fingerprint = fingerprint(projectId, clipAssetId);
		var replay = this.repository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (replay.isPresent()) {
			return HandPoseJobResponse.from(requireSameRequest(replay.get(), fingerprint));
		}
		var input = this.assetCatalog.getClipAssetInputSummary(projectId, clipAssetId);
		ensureEligible(input);
		var natural = this.repository
			.findByProjectIdAndClipAssetIdAndInputSha256AndAlgorithmKeyAndAlgorithmVersionAndModelSha256AndSamplingFps(
					projectId, clipAssetId, input.inputDigest(), HandPoseProfile.ALGORITHM_KEY,
					HandPoseProfile.ALGORITHM_VERSION, this.modelSha256, HandPoseProfile.SAMPLING_FPS);
		if (natural.isPresent()) {
			return HandPoseJobResponse.from(requireSameRequest(natural.get(), fingerprint));
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		try {
			var job = this.repository
				.saveAndFlush(AiCandidateJob.queue(projectId, clipAssetId, this.modelSha256, input.inputDigest(),
						input.inputSizeBytes(), input.contentType(), idempotencyKey, fingerprint, actorId, now));
			this.attemptQueue.enqueue(job, 1, now);
			this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
					"AI_HAND_POSE_JOB_QUEUED", "AIJob", job.getAiJobId().toString(), "1", AuditOutcome.SUCCEEDED,
					"MEDIAPIPE_HAND_LANDMARKER", requestId, traceId, "ROLE_" + projectRole.name(),
					java.util.Map.of("clip_asset_id", clipAssetId.toString(), "model_sha256", this.modelSha256,
							"sampling_fps", Integer.toString(HandPoseProfile.SAMPLING_FPS))));
			return HandPoseJobResponse.from(job);
		}
		catch (DataIntegrityViolationException exception) {
			var concurrent = this.repository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
			if (concurrent.isPresent()) {
				return HandPoseJobResponse.from(requireSameRequest(concurrent.get(), fingerprint));
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	HandPoseJobResponse get(UUID projectId, UUID aiJobId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var job = this.repository.findById(aiJobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new HandPoseJobException("AI_HAND_POSE_JOB_NOT_FOUND",
					"The hand pose job was not found.", 404));
		return HandPoseJobResponse.from(job);
	}

	private void validateModel() {
		if (!this.modelSha256.matches("^[a-f0-9]{64}$")) {
			throw new HandPoseJobException("AI_HAND_POSE_MODEL_NOT_CONFIGURED",
					"An approved offline Hand Landmarker model is not configured.", 409);
		}
	}

	private static void ensureEligible(ClipAssetInputSummary input) {
		if (input.status() != ClipAssetStatus.INGESTED || !input.contentType().startsWith("video/")) {
			throw new HandPoseJobException("CLIP_ASSET_NOT_READY_FOR_AI_CANDIDATES",
					"Only a media-processed video clip asset can receive hand pose candidates.", 409);
		}
	}

	private static AiCandidateJob requireSameRequest(AiCandidateJob job, String fingerprint) {
		if (!MessageDigest.isEqual(job.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new HandPoseJobException("AI_HAND_POSE_IDEMPOTENCY_KEY_REUSED",
					"The idempotency key was already used for a different hand pose job.", 409);
		}
		return job;
	}

	private String fingerprint(UUID projectId, UUID clipAssetId) {
		return HandPoseProfile.sha256(String.join("\n", projectId.toString(), clipAssetId.toString(),
				HandPoseProfile.fingerprint(this.modelSha256)));
	}

}
