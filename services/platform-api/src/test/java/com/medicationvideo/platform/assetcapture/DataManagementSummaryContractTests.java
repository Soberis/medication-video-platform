package com.medicationvideo.platform.assetcapture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class DataManagementSummaryContractTests {

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
	void returnsProjectScopedAggregateCountersWithoutPerVideoCalls() throws Exception {
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", PROJECT_ID).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total_videos").value(0))
			.andExpect(jsonPath("$.total_duration").value(0))
			.andExpect(jsonPath("$.storage_bytes").value(0))
			.andExpect(jsonPath("$.pending_annotation").value(0))
			.andExpect(jsonPath("$.pending_review").value(0))
			.andExpect(jsonPath("$.pending_cleaning").value(0))
			.andExpect(jsonPath("$.usable_count").value(0));
	}

}
