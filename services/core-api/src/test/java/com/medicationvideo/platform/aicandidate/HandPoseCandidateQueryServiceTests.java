package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.medicationvideo.platform.iamproject.ProjectAccessService;

import tools.jackson.databind.ObjectMapper;

class HandPoseCandidateQueryServiceTests {

	private static final UUID PROJECT_ID = UUID.randomUUID();

	private static final UUID CLIP_ASSET_ID = UUID.randomUUID();

	@Test
	void returnsTheHandPoseResultFromTheAlgorithmScopedLookup() {
		var repository = mock(AiCandidateJobRepository.class);
		var attempts = mock(AiCandidateJobAttemptRepository.class);
		var jdbcTemplate = mock(JdbcTemplate.class);
		var job = AiCandidateJob.queue(PROJECT_ID, CLIP_ASSET_ID, "a".repeat(64), "b".repeat(64), 100, "video/mp4",
				"hand-pose-query-key", "c".repeat(64), "actor", Instant.EPOCH);
		job.succeed(Instant.EPOCH.plusSeconds(1), HandPoseResult.HAND_DETECTED);
		when(repository.findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(PROJECT_ID, CLIP_ASSET_ID,
				HandPoseProfile.ALGORITHM_KEY))
			.thenReturn(Optional.of(job));
		doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(job.getAiJobId()));
		var service = new HandPoseCandidateQueryService(mock(ProjectAccessService.class), repository, attempts,
				jdbcTemplate, mock(ObjectMapper.class), 3);

		var response = service.getLatest(PROJECT_ID, CLIP_ASSET_ID, "actor");

		assertEquals(job.getAiJobId(), response.job().aiJobId());
		assertEquals("MEDIAPIPE_HAND_LANDMARKER", response.job().algorithmKey());
		assertEquals("HAND_DETECTED", response.job().handResult());
		verify(repository).findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(PROJECT_ID,
				CLIP_ASSET_ID, HandPoseProfile.ALGORITHM_KEY);
	}

	@Test
	void reportsNotFoundWhenNoHandPoseJobExists() {
		var repository = mock(AiCandidateJobRepository.class);
		var jdbcTemplate = mock(JdbcTemplate.class);
		var service = new HandPoseCandidateQueryService(mock(ProjectAccessService.class), repository,
				mock(AiCandidateJobAttemptRepository.class), jdbcTemplate, mock(ObjectMapper.class), 3);

		var exception = assertThrows(HandPoseJobException.class,
				() -> service.getLatest(PROJECT_ID, CLIP_ASSET_ID, "actor"));

		assertEquals("AI_HAND_POSE_JOB_NOT_FOUND", exception.getErrorCode());
		verify(repository).findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(PROJECT_ID,
				CLIP_ASSET_ID, HandPoseProfile.ALGORITHM_KEY);
		verifyNoInteractions(jdbcTemplate);
	}

}
