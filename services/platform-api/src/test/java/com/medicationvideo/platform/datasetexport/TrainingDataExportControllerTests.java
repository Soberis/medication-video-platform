package com.medicationvideo.platform.datasetexport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;
import com.medicationvideo.platform.iamproject.PlatformActor;

class TrainingDataExportControllerTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);

	private static final String ADMIN_ACTOR = "training.admin";

	private MinimalTrainingDataExportService service;

	private ProjectAccessService projectAccessService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.service = mock(MinimalTrainingDataExportService.class);
		this.projectAccessService = mock(ProjectAccessService.class);
		var preview = new TrainingDataExportPreviewResponse(MinimalTrainingDataExportService.SCHEMA_VERSION, 2, 5, 3, 2,
				"one MediaPipe Candidate hand observation inside one APPROVED human action interval [start_ms, end_ms)",
				MinimalTrainingDataExportService.FILE_NAME, MinimalTrainingDataExportService.MEDIA_TYPE, false);
		when(this.service.preview(PROJECT_ID)).thenReturn(preview);
		when(this.service.download(PROJECT_ID)).thenReturn(new byte[] { 0x50, 0x41, 0x52, 0x31 });
		var jsonMapper = JsonMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();
		this.mockMvc = MockMvcBuilders
			.standaloneSetup(new TrainingDataExportController(this.service, this.projectAccessService))
			.setControllerAdvice(new TrainingDataExportExceptionHandler())
			.setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(jsonMapper))
			.build();
	}

	@Test
	void previewsOnlyAfterProjectAdministratorAuthorizationAndNeverCaches() throws Exception {
		when(this.projectAccessService.requireAdministrator(PROJECT_ID, ADMIN_ACTOR)).thenReturn(ProjectRole.ADMIN);

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/training-data/preview", PROJECT_ID)
				.principal(authentication("ROLE_ADMIN", ADMIN_ACTOR)))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.training_row_count").value(3))
			.andExpect(jsonPath("$.eligible_video_count").value(2))
			.andExpect(jsonPath("$.includes_mp4").value(false));
		verify(this.service).preview(PROJECT_ID);
	}

	@Test
	void downloadsAParquetAttachmentWithoutCaching() throws Exception {
		when(this.projectAccessService.requireAdministrator(PROJECT_ID, ADMIN_ACTOR)).thenReturn(ProjectRole.ADMIN);

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/training-data/download", PROJECT_ID)
				.principal(authentication("ROLE_ADMIN", ADMIN_ACTOR)))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().string("Content-Type", MinimalTrainingDataExportService.MEDIA_TYPE))
			.andExpect(header().string("Content-Disposition",
					org.hamcrest.Matchers.containsString("attachment; filename=\"training_data.parquet\"")))
			.andExpect(header().doesNotExist("X-MP4-Download"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
				.bytes(new byte[] { 0x50, 0x41, 0x52, 0x31 }));
		verify(this.service).download(PROJECT_ID);
	}

	@Test
	void doesNotCallExportWhenTheExistingAuthorizationBoundaryRejectsTheActor() throws Exception {
		when(this.projectAccessService.requireAdministrator(PROJECT_ID, ADMIN_ACTOR))
			.thenThrow(new ProjectAccessException("PROJECT_ACCESS_DENIED",
					"Project administrator membership is required.", 403));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/training-data/preview", PROJECT_ID)
				.principal(authentication("ROLE_MEMBER", ADMIN_ACTOR)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"))
			.andExpect(header().string("Cache-Control", "no-store"));
		verify(this.service, never()).preview(any());
	}

	private static Principal authentication(String role, String actorId) {
		return new UsernamePasswordAuthenticationToken(new PlatformActor(PROJECT_ID, actorId), null,
				List.of(new SimpleGrantedAuthority(role)));
	}

}
