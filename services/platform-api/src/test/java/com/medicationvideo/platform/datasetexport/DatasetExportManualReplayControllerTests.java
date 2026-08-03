package com.medicationvideo.platform.datasetexport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import com.medicationvideo.platform.iamproject.PlatformActor;

class DatasetExportManualReplayControllerTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);

	private static final UUID EXPORT_ID = new UUID(0, 2);

	private static final UUID DEAD_LETTER_ID = new UUID(0, 3);

	private static final String ADMIN_ACTOR = "export.admin";

	private DatasetExportManualReplayService service;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.service = mock(DatasetExportManualReplayService.class);
		var validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		this.mockMvc = MockMvcBuilders.standaloneSetup(new DatasetExportManualReplayController(this.service))
			.setControllerAdvice(new DatasetExportManualReplayExceptionHandler())
			.setValidator(validator)
			.setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
				.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build()))
			.build();
	}

	@Test
	void acceptsAnAdministratorReplayAndNeverCachesTheResponse() throws Exception {
		var replayRequest = new DatasetExportManualReplayRequest(DEAD_LETTER_ID, UUID.randomUUID(), 1,
				DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED, "d".repeat(64),
				DatasetExportManualReplayReason.CONFIGURATION_FIXED, "change-ref-0001");
		var replay = DatasetExportManualReplay.create(PROJECT_ID, EXPORT_ID, replayRequest, 2, ADMIN_ACTOR,
				"dataset-replay-0001", "0".repeat(64), Instant.parse("2026-08-03T07:00:00Z"));
		when(this.service.replay(eq(PROJECT_ID), eq(ADMIN_ACTOR), eq(EXPORT_ID), eq("dataset-replay-0001"), anyString(),
				any(), any(DatasetExportManualReplayRequest.class)))
			.thenReturn(replay);

		this.mockMvc
			.perform(post("/api/v1/dataset-exports/{datasetExportId}/replays", EXPORT_ID)
				.principal(authentication("ROLE_ADMIN", ADMIN_ACTOR))
				.header("Idempotency-Key", "dataset-replay-0001")
				.contentType("application/json")
				.content(validRequest()))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.dataset_export_id").value(EXPORT_ID.toString()))
			.andExpect(jsonPath("$.dead_letter_id").value(DEAD_LETTER_ID.toString()))
			.andExpect(jsonPath("$.new_attempt").value(2))
			.andExpect(jsonPath("$.resulting_export_status").value("QUEUED"));
	}

	@Test
	void rejectsNonAdministratorsBeforeCallingTheService() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/dataset-exports/{datasetExportId}/replays", EXPORT_ID)
				.principal(authentication("ROLE_MEMBER", "export.member"))
				.header("Idempotency-Key", "dataset-replay-0001")
				.contentType("application/json")
				.content(validRequest()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("DATASET_EXPORT_REPLAY_ACCESS_DENIED"));
		verify(this.service, never()).replay(any(), anyString(), any(), anyString(), anyString(), any(), any());
	}

	@Test
	void rejectsInvalidRequestFieldsAndUnknownBodyContent() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/dataset-exports/{datasetExportId}/replays", EXPORT_ID)
				.principal(authentication("ROLE_ADMIN", ADMIN_ACTOR))
				.header("Idempotency-Key", "dataset-replay-0001")
				.contentType("application/json")
				.content(validRequestWithUnknownRawBody()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("INVALID_DATASET_EXPORT_REPLAY_REQUEST"));
		verify(this.service, never()).replay(any(), anyString(), any(), anyString(), anyString(), any(), any());
	}

	private static Principal authentication(String role, String actorId) {
		return new UsernamePasswordAuthenticationToken(new PlatformActor(PROJECT_ID, actorId), null,
				List.of(new SimpleGrantedAuthority(role)));
	}

	private static String validRequest() {
		return """
				{"dead_letter_id":"%s","source_message_id":"00000000-0000-0000-0000-000000000004",
				"source_attempt":1,"dead_letter_reason":"DATASET_EXPORT_JOB_ACQUIRE_REJECTED",
				"body_sha256":"%s","operator_reason_code":"CONFIGURATION_FIXED",
				"change_reference":"change-ref-0001"}
				""".formatted(DEAD_LETTER_ID, "d".repeat(64));
	}

	private static String validRequestWithUnknownRawBody() {
		return """
				{"dead_letter_id":"%s","source_message_id":"00000000-0000-0000-0000-000000000004",
				"source_attempt":1,"dead_letter_reason":"DATASET_EXPORT_JOB_ACQUIRE_REJECTED",
				"body_sha256":"%s","operator_reason_code":"CONFIGURATION_FIXED",
				"change_reference":"change-ref-0001","raw_body":"sensitive-payload"}
				""".formatted(DEAD_LETTER_ID, "d".repeat(64));
	}

}
