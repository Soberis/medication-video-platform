package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.assetcapture.ClipAssetInputSummary;
import com.medicationvideo.platform.assetcapture.ClipAssetStatus;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;

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

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

	private AssetCatalogQueryService assetCatalog;

	private ActionJobEnvelopeFactory actionEnvelopeFactory;

	@BeforeEach
	void setUp() {
		this.jobRepository = mock(AiCandidateJobRepository.class);
		this.attemptRepository = mock(AiCandidateJobAttemptRepository.class);
		this.jdbcTemplate = mock(JdbcTemplate.class);
		this.assetCatalog = mock(AssetCatalogQueryService.class);
		this.actionEnvelopeFactory = mock(ActionJobEnvelopeFactory.class);
		this.objectMapper = new ObjectMapper();
		this.service = new ActionCandidateJobService(this.jobRepository, this.attemptRepository, this.jdbcTemplate,
				this.objectMapper, null, this.assetCatalog, null, null, null, null, this.actionEnvelopeFactory);
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
		when(job.getAlgorithmKey()).thenReturn(AiCandidateJob.ACTION_ALGORITHM_KEY);
		when(this.jobRepository.findForUpdate(JOB_ID)).thenReturn(Optional.of(job));
		return stubAttempt(messageId);
	}

	/**
	 * Registers a leased attempt without touching the job repository, so a caller can
	 * keep its own job stub (for example one that reports RUNNING).
	 */
	private AiCandidateJobAttempt stubAttempt(UUID messageId) {
		var now = Instant.now();
		var attempt = AiCandidateJobAttempt.queue(JOB_ID, 1, messageId, AiLeaseCapabilityFactory.sha256("token"), now);
		attempt.acquire("worker", now, Duration.ofMinutes(5));
		when(this.attemptRepository.findByAiJobIdAndAttempt(JOB_ID, 1)).thenReturn(Optional.of(attempt));
		return attempt;
	}

	@Test
	void rejectsSegmentLabelsOutsideTheActiveSchema() {
		var messageId = UUID.randomUUID();
		prepareRunningJob(messageId);
		var request = result(messageId,
				List.of(new ActionCandidateSegmentItem(0L, 100L, "not_a_label", 0.5, 1, List.of())));

		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", request, "worker"));

		assertEquals("INVALID_SEGMENT_LABEL", exception.getErrorCode());
	}

	@Test
	void rejectsOverlappingSegments() {
		var messageId = UUID.randomUUID();
		prepareRunningJob(messageId);
		var request = result(messageId,
				List.of(new ActionCandidateSegmentItem(0L, 1000L, "draw_liquid", 0.5, 1, List.of()),
						new ActionCandidateSegmentItem(500L, 1500L, "draw_liquid", 0.5, 1, List.of())));

		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", request, "worker"));

		assertEquals("OVERLAPPING_SEGMENT_INTERVAL", exception.getErrorCode());
	}

	@Test
	void rejectsMissingBoundsBeforeSortingSegments() {
		var messageId = UUID.randomUUID();
		prepareRunningJob(messageId);
		var request = result(messageId,
				List.of(new ActionCandidateSegmentItem(0L, 100L, "draw_liquid", null, 1, List.of()),
						new ActionCandidateSegmentItem(null, 200L, "draw_liquid", null, 1, List.of())));
		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", request, "worker"));
		assertEquals("INVALID_SEGMENT_INTERVAL", exception.getErrorCode());
	}

	@Test
	void confidenceIsOptionalButCannotExceedItsRange() {
		try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
			var validator = factory.getValidator();
			assertEquals(0,
					validator.validate(new ActionCandidateSegmentItem(0L, 100L, "draw_liquid", null, 1, List.of()))
						.size());
			assertFalse(validator.validate(new ActionCandidateSegmentItem(0L, 100L, "draw_liquid", 1.1, 1, List.of()))
				.isEmpty());
		}
	}

	@Test
	void rejectsSegmentsBeyondTheReportedDuration() {
		var messageId = UUID.randomUUID();
		prepareRunningJob(messageId);
		var request = result(messageId,
				List.of(new ActionCandidateSegmentItem(0L, 60_000L, "draw_liquid", 0.5, 1, List.of())));

		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", request, "worker"));

		assertEquals("INVALID_SEGMENT_INTERVAL", exception.getErrorCode());
	}

	@Test
	void rejectsResultsExceedingTheSegmentLimit() {
		var messageId = UUID.randomUUID();
		prepareRunningJob(messageId);
		var segments = new ArrayList<ActionCandidateSegmentItem>();
		for (var index = 0; index <= ActionCandidateJobService.MAX_RESULT_SEGMENTS; index++) {
			segments.add(new ActionCandidateSegmentItem(index * 2L, index * 2L + 1L, "draw_liquid", 0.5, 1, List.of()));
		}

		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.acceptResult(JOB_ID, 1, "token", result(messageId, segments), "worker"));

		assertEquals("AI_ACTION_RESULT_TOO_LARGE", exception.getErrorCode());
	}

	private void prepareRunningJob(UUID messageId) {
		var job = mock(AiCandidateJob.class);
		when(job.getAlgorithmKey()).thenReturn(AiCandidateJob.ACTION_ALGORITHM_KEY);
		when(job.getStatus()).thenReturn(AiCandidateJobStatus.RUNNING);
		when(job.getClipAssetId()).thenReturn(CLIP_ASSET_ID);
		when(this.jobRepository.findForUpdate(JOB_ID)).thenReturn(Optional.of(job));
		stubAttempt(messageId);
		when(this.assetCatalog.resolveCaptureGroupId(CLIP_ASSET_ID)).thenReturn(CAPTURE_GROUP_ID);
		when(this.actionEnvelopeFactory.resolveAllowedLabels()).thenReturn(List.of("draw_liquid"));
	}

	private static ActionCandidateResultRequest result(UUID messageId, List<ActionCandidateSegmentItem> segments) {
		var time = Instant.parse("2026-09-10T00:00:00Z");
		return new ActionCandidateResultRequest(messageId, "qwen3-vl-8b", "v1", 1, 19000L, 5000L, time, time, segments);
	}

	@Test
	@SuppressWarnings("unchecked")
	void listByCaptureGroupQueriesRepository() {
		when(this.actionEnvelopeFactory.resolveTaskPrimaryClipAssetId(PROJECT_ID, CAPTURE_GROUP_ID))
			.thenReturn(CLIP_ASSET_ID);
		when(this.jdbcTemplate.query(anyString(), any(RowMapper.class), eq(PROJECT_ID), eq(CAPTURE_GROUP_ID),
				eq(CLIP_ASSET_ID)))
			.thenReturn(List.of());

		var results = this.service.listByCaptureGroup(PROJECT_ID, CAPTURE_GROUP_ID);

		assertEquals(0, results.size());
		verify(this.jdbcTemplate).query(anyString(), any(RowMapper.class), eq(PROJECT_ID), eq(CAPTURE_GROUP_ID),
				eq(CLIP_ASSET_ID));
	}

	@Test
	void queueActionCandidateJobResolvesNullRequestIdAndTraceId() {
		when(this.actionEnvelopeFactory.resolveLabelSnapshot(PROJECT_ID, CAPTURE_GROUP_ID))
			.thenReturn(new ActionLabelSnapshot(UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64),
					List.of("draw_liquid"), CLIP_ASSET_ID));
		var auditRecorder = mock(AuditEventRecorder.class);
		var serviceWithAudit = new ActionCandidateJobService(this.jobRepository, this.attemptRepository,
				this.jdbcTemplate, this.objectMapper, null, this.assetCatalog, null, auditRecorder, null, null,
				this.actionEnvelopeFactory);

		when(this.assetCatalog.resolvePrimaryClipAssetId(CAPTURE_GROUP_ID)).thenReturn(CLIP_ASSET_ID);
		when(this.assetCatalog.getClipAssetInputSummary(PROJECT_ID, CLIP_ASSET_ID))
			.thenReturn(new ClipAssetInputSummary(CLIP_ASSET_ID, PROJECT_ID, "video/mp4", 1000L, "a".repeat(64),
					ClipAssetStatus.INGESTED, "OVERVIEW", true, Instant.EPOCH, Instant.EPOCH));
		when(this.jobRepository.findAllByProjectIdAndClipAssetIdOrderByCreatedAtDesc(PROJECT_ID, CLIP_ASSET_ID))
			.thenReturn(List.of());
		var queuedJob = AiCandidateJob.queueAction(PROJECT_ID, CLIP_ASSET_ID, AiCandidateJob.ACTION_MODEL_SHA256,
				"a".repeat(64), 1000L, "video/mp4", "idemp-1234567890123456", "b".repeat(64), "actor-1", Instant.now());
		when(this.jobRepository.save(any())).thenReturn(queuedJob);

		var response = serviceWithAudit.queueActionCandidateJob(PROJECT_ID, CAPTURE_GROUP_ID, "actor-1",
				"idemp-1234567890123456", null, null);

		assertNotNull(response);
		var captor = ArgumentCaptor.forClass(AuditEventCommand.class);
		verify(auditRecorder).record(captor.capture());
		var recorded = captor.getValue();
		assertNotNull(recorded.requestId());
		assertFalse(recorded.requestId().isBlank());
		assertNotNull(recorded.traceId());
		assertFalse(recorded.traceId().isBlank());
	}

}
