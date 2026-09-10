package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import tools.jackson.databind.ObjectMapper;

class ActionCandidateJobServiceTests {

	private AiCandidateJobRepository jobRepository;

	private AiCandidateJobAttemptRepository attemptRepository;

	private JdbcTemplate jdbcTemplate;

	private ObjectMapper objectMapper;

	private ActionCandidateJobService service;

	private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final UUID CAPTURE_GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@BeforeEach
	void setUp() {
		this.jobRepository = mock(AiCandidateJobRepository.class);
		this.attemptRepository = mock(AiCandidateJobAttemptRepository.class);
		this.jdbcTemplate = mock(JdbcTemplate.class);
		this.objectMapper = new ObjectMapper();
		this.service = new ActionCandidateJobService(this.jobRepository, this.attemptRepository, this.jdbcTemplate,
				this.objectMapper);
	}

	@Test
	void throwsWhenJobNotFound() {
		when(this.jobRepository.findForUpdate(JOB_ID)).thenReturn(Optional.empty());

		var request = new ActionCandidateResultRequest(UUID.randomUUID(), "qwen3-vl-8b", "v1", 1, 19000L, 5000L,
				Instant.now(), Instant.now(), List.of());

		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "invalid-token", request, "worker-1"));

		assertEquals("AI_ACTION_JOB_NOT_FOUND", exception.getErrorCode());
	}

	@Test
	@SuppressWarnings("unchecked")
	void listByCaptureGroupQueriesRepository() {
		when(this.jdbcTemplate.query(anyString(), any(RowMapper.class), eq(PROJECT_ID), eq(CAPTURE_GROUP_ID)))
			.thenReturn(List.of());

		var results = this.service.listByCaptureGroup(PROJECT_ID, CAPTURE_GROUP_ID);

		assertEquals(0, results.size());
		verify(this.jdbcTemplate).query(anyString(), any(RowMapper.class), eq(PROJECT_ID), eq(CAPTURE_GROUP_ID));
	}

}
