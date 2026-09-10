package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Duration;
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
	void rejectsHandPoseJobWithoutReadingOrWritingResults() {
		var job = mock(AiCandidateJob.class);
		when(job.getAlgorithmKey()).thenReturn(HandPoseProfile.ALGORITHM_KEY);
		when(this.jobRepository.findForUpdate(JOB_ID)).thenReturn(Optional.of(job));
		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", result(UUID.randomUUID(), List.of()), "worker"));
		assertEquals("AI_ACTION_JOB_TYPE_MISMATCH", exception.getErrorCode());
		verifyNoInteractions(this.attemptRepository, this.jdbcTemplate);
	}

	@Test
	void duplicateReceiptStillRequiresTheLeasedWorkerAndMessage() {
		var messageId = UUID.randomUUID();
		var attempt = prepareAttempt(messageId);
		var request = result(messageId, List.of());
		assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", request, "other-worker"));
		assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "wrong-token", request, "worker"));
		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", result(UUID.randomUUID(), List.of()), "worker"));
		assertEquals("AI_ACTION_RESULT_IDENTITY_MISMATCH", exception.getErrorCode());
		assertEquals(AiCandidateJobAttemptStatus.RUNNING, attempt.getStatus());
		verifyNoInteractions(this.jdbcTemplate);
	}

	@Test
	void replayDigestIncludesMetadataAndUnambiguousIntervals() {
		var messageId = UUID.randomUUID();
		prepareAttempt(messageId);
		var original = result(messageId,
				List.of(new ActionCandidateSegmentItem(1L, 234L, "draw_liquid", 0.8, 1, List.of())));
		when(this.jdbcTemplate.queryForList(anyString(), eq(String.class), eq(messageId)))
			.thenReturn(List.of(HandPoseProfile.sha256(this.objectMapper.writeValueAsString(original))));
		this.service.acceptResult(JOB_ID, 1, "token", original, "worker");
		for (var changed : List.of(
				result(messageId, List.of(new ActionCandidateSegmentItem(12L, 34L, "draw_liquid", 0.8, 1, List.of()))),
				result(messageId,
						List.of(new ActionCandidateSegmentItem(1L, 234L, "draw_liquid", 0.9, 1, List.of()))))) {
			var exception = assertThrows(HandPoseJobException.class,
					() -> this.service.acceptResult(JOB_ID, 1, "token", changed, "worker"));
			assertEquals("AI_ACTION_RESULT_MESSAGE_REUSED", exception.getErrorCode());
		}
	}

	private AiCandidateJobAttempt prepareAttempt(UUID messageId) {
		var job = mock(AiCandidateJob.class);
		when(job.getAlgorithmKey()).thenReturn("QWEN3_VL_ACTION");
		when(this.jobRepository.findForUpdate(JOB_ID)).thenReturn(Optional.of(job));
		var now = Instant.now();
		var attempt = AiCandidateJobAttempt.queue(JOB_ID, 1, messageId, AiLeaseCapabilityFactory.sha256("token"), now);
		attempt.acquire("worker", now, Duration.ofMinutes(5));
		when(this.attemptRepository.findByAiJobIdAndAttempt(JOB_ID, 1)).thenReturn(Optional.of(attempt));
		return attempt;
	}

	private static ActionCandidateResultRequest result(UUID messageId, List<ActionCandidateSegmentItem> segments) {
		var time = Instant.parse("2026-09-10T00:00:00Z");
		return new ActionCandidateResultRequest(messageId, "qwen3-vl-8b", "v1", 1, 19000L, 5000L, time, time, segments);
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
