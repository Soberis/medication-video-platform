package com.medicationvideo.platform.aicandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.ClipAssetAnalysisReadService;

@Service
class AiJobExecutionService {

	private final AiCandidateJobRepository jobRepository;

	private final AiCandidateJobAttemptRepository attemptRepository;

	private final ClipAssetAnalysisReadService assetRead;

	private final JdbcTemplate jdbcTemplate;

	private final Duration leaseDuration;

	private final Duration heartbeatInterval;

	private final Duration inputAuthorizationLifetime;

	private final Duration resultClockSkew;

	AiJobExecutionService(AiCandidateJobRepository jobRepository, AiCandidateJobAttemptRepository attemptRepository,
			ClipAssetAnalysisReadService assetRead, JdbcTemplate jdbcTemplate,
			@Value("${platform.ai.hand-pose.lease-duration:PT30M}") Duration leaseDuration,
			@Value("${platform.ai.hand-pose.heartbeat-interval:PT5M}") Duration heartbeatInterval,
			@Value("${platform.ai.hand-pose.input-authorization-ttl:PT15M}") Duration inputAuthorizationLifetime,
			@Value("${platform.ai.hand-pose.result-clock-skew:PT5M}") Duration resultClockSkew) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.assetRead = assetRead;
		this.jdbcTemplate = jdbcTemplate;
		this.leaseDuration = leaseDuration;
		this.heartbeatInterval = heartbeatInterval;
		this.inputAuthorizationLifetime = inputAuthorizationLifetime;
		this.resultClockSkew = resultClockSkew;
	}

	@Transactional
	AiWorkerLeaseResponse acquire(UUID jobId, int attemptNumber, String leaseToken, AiWorkerLeaseRequest request) {
		var now = now();
		var job = lockJob(jobId);
		var attempt = lockAttempt(jobId, attemptNumber);
		requireLease(attempt, leaseToken);
		attempt.acquire(request.workerId(), now, this.leaseDuration);
		job.start(now);
		var grant = this.assetRead.authorize(job.getProjectId(), job.getClipAssetId(), job.getInputSha256(),
				this.inputAuthorizationLifetime, now);
		return new AiWorkerLeaseResponse(job.getAiJobId(), attempt.getAttempt(), grant.inputUrl(), grant.expiresAt(),
				attempt.getLeaseUntil(), this.heartbeatInterval.toSeconds(), job.getInputSha256(),
				job.getInputSizeBytes(), job.getContentType(), job.getModelSha256(), job.getSamplingFps(),
				job.getProfileSha256());
	}

	@Transactional
	AiWorkerHeartbeatResponse heartbeat(UUID jobId, int attemptNumber, String leaseToken,
			AiWorkerLeaseRequest request) {
		var now = now();
		var attempt = lockAttempt(jobId, attemptNumber);
		requireLease(attempt, leaseToken);
		attempt.heartbeat(request.workerId(), now, this.leaseDuration);
		return new AiWorkerHeartbeatResponse(jobId, attempt.getAttempt(), attempt.getLeaseUntil(),
				this.heartbeatInterval.toSeconds());
	}

	@Transactional
	void acceptResult(UUID jobId, int attemptNumber, String leaseToken, AiHandPoseResultRequest request) {
		var now = now();
		var job = lockJob(jobId);
		var attempt = lockAttempt(jobId, attemptNumber);
		if (!jobId.equals(request.jobId()) || attemptNumber != request.attempt()
				|| !attempt.getMessageId().equals(request.messageId())) {
			throw new HandPoseJobException("AI_HAND_POSE_RESULT_IDENTITY_MISMATCH",
					"The result does not identify the current AI job attempt.", 409);
		}
		if (!constantTimeEquals(leaseToken, request.leaseToken())
				|| !constantTimeEquals(job.getInputSha256(), request.inputSha256())
				|| !constantTimeEquals(job.getModelSha256(), request.modelSha256())
				|| !constantTimeEquals(job.getProfileSha256(), request.profileSha256())) {
			throw new HandPoseJobException("AI_HAND_POSE_RESULT_DIGEST_MISMATCH",
					"The result does not match the frozen AI job inputs.", 409);
		}
		var digest = HandPoseProfile.sha256(request.toString());
		var existing = this.jdbcTemplate
			.queryForList("SELECT result_sha256 FROM ai_job_result_inbox WHERE message_id = ?", request.messageId());
		if (!existing.isEmpty()) {
			if (digest.equals(existing.getFirst().get("result_sha256"))) {
				return;
			}
			throw new HandPoseJobException("AI_HAND_POSE_RESULT_MESSAGE_REUSED",
					"The result message identifier was reused with different content.", 409);
		}
		requireLease(attempt, leaseToken);
		attempt.requireActiveLease(now);
		if (job.getStatus() != AiCandidateJobStatus.RUNNING) {
			throw new HandPoseJobException("STALE_ATTEMPT", "The AI job is no longer running.", 409);
		}
		if (request.startedAt().isBefore(job.getCreatedAt().minus(this.resultClockSkew))
				|| request.completedAt().isAfter(now.plus(this.resultClockSkew))) {
			throw new HandPoseJobException("AI_HAND_POSE_RESULT_TIME_INVALID",
					"The Worker result timestamps exceed the approved clock-skew window.", 409);
		}
		var receivedAt = Timestamp.from(now);
		for (var candidate : request.candidates()) {
			this.jdbcTemplate.update("""
					INSERT INTO candidate_annotation (
					    candidate_annotation_id, ai_job_id, project_id, clip_asset_id, frame_timestamp_ms, hand_index,
					    handedness, handedness_score, bounding_box, landmarks, image_width, image_height,
					    valid_landmark_count, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?, ?, ?, ?)
					""", UUID.randomUUID(), job.getAiJobId(), job.getProjectId(), job.getClipAssetId(),
					candidate.frameTimestampMs(), candidate.handIndex(), candidate.handedness(),
					candidate.handednessScore(), candidate.boundingBox().toString(), candidate.landmarks().toString(),
					candidate.imageWidth(), candidate.imageHeight(), candidate.validLandmarkCount(), receivedAt);
		}
		this.jdbcTemplate.update(
				"INSERT INTO ai_job_result_inbox (message_id, ai_job_id, attempt, result_sha256, received_at) VALUES (?, ?, ?, ?, ?)",
				request.messageId(), jobId, attemptNumber, digest, receivedAt);
		attempt.succeed(now);
		job.succeed(now);
	}

	private AiCandidateJob lockJob(UUID jobId) {
		return this.jobRepository.findForUpdate(jobId)
			.orElseThrow(() -> new HandPoseJobException("AI_HAND_POSE_JOB_NOT_FOUND",
					"The hand pose job was not found.", 404));
	}

	private AiCandidateJobAttempt lockAttempt(UUID jobId, int attemptNumber) {
		return this.attemptRepository.findByAiJobIdAndAttempt(jobId, attemptNumber)
			.orElseThrow(() -> new HandPoseJobException("STALE_ATTEMPT", "The AI job attempt was not found.", 409));
	}

	private static void requireLease(AiCandidateJobAttempt attempt, String token) {
		if (!attempt.matchesLeaseHash(AiLeaseCapabilityFactory.sha256(token))) {
			throw new HandPoseJobException("AI_HAND_POSE_LEASE_INVALID", "The AI job lease token is invalid.", 409);
		}
	}

	private static boolean constantTimeEquals(String first, String second) {
		return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
				second.getBytes(StandardCharsets.US_ASCII));
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
