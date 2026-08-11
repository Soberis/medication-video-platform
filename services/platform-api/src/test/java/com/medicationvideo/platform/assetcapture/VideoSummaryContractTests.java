package com.medicationvideo.platform.assetcapture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoSummaryContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void ensureProject() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
	}

	@Test
	void exposesTheBoundedVideoSummaryReadModelWithTheV118Defaults() throws Exception {
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/videos", PROJECT_ID).header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.next_cursor").value(nullValue()));
	}

}
