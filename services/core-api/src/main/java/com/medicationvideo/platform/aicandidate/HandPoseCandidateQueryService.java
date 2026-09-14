package com.medicationvideo.platform.aicandidate;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.iamproject.ProjectAccessService;

import tools.jackson.databind.ObjectMapper;

@Service
class HandPoseCandidateQueryService {

	private final ProjectAccessService projectAccessService;

	private final AiCandidateJobRepository jobRepository;

	private final AiCandidateJobAttemptRepository attemptRepository;

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final int maxAttempts;

	HandPoseCandidateQueryService(ProjectAccessService projectAccessService, AiCandidateJobRepository jobRepository,
			AiCandidateJobAttemptRepository attemptRepository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			@Value("${platform.ai.hand-pose.max-attempts:3}") int maxAttempts) {
		this.projectAccessService = projectAccessService;
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		HandPoseRetryPolicy.validateMaxAttempts(maxAttempts);
		this.maxAttempts = maxAttempts;
	}

	@Transactional(readOnly = true)
	HandPoseCandidateResponse getLatest(UUID projectId, UUID clipAssetId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var job = this.jobRepository
			.findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(projectId, clipAssetId,
					HandPoseProfile.ALGORITHM_KEY)
			.orElseThrow(() -> new HandPoseJobException("AI_HAND_POSE_JOB_NOT_FOUND",
					"No hand pose candidate job exists for this clip asset.", 404));
		var candidates = this.jdbcTemplate.query("""
				SELECT frame_timestamp_ms, image_width, image_height, hand_index, handedness, handedness_score,
				       bounding_box, landmarks, valid_landmark_count
				FROM candidate_annotation
				WHERE ai_job_id = ?
				ORDER BY frame_timestamp_ms, hand_index
				""",
				(resultSet, ignored) -> new HandPoseCandidate(resultSet.getLong("frame_timestamp_ms"),
						resultSet.getInt("image_width"), resultSet.getInt("image_height"),
						resultSet.getInt("hand_index"), resultSet.getString("handedness"),
						resultSet.getDouble("handedness_score"), readJson(resultSet.getString("bounding_box")),
						readJson(resultSet.getString("landmarks")), resultSet.getInt("valid_landmark_count")),
				job.getAiJobId());
		var attempt = this.attemptRepository.findFirstByAiJobIdOrderByAttemptDesc(job.getAiJobId()).orElse(null);
		return new HandPoseCandidateResponse(HandPoseJobResponse.from(job, attempt, this.maxAttempts),
				List.copyOf(candidates));
	}

	private tools.jackson.databind.JsonNode readJson(String source) {
		return this.objectMapper.readTree(source);
	}

}
