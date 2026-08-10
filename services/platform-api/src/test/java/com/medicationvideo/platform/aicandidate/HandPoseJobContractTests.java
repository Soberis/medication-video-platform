package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HandPoseJobContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearState() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM candidate_annotation");
		this.jdbcTemplate.update("DELETE FROM ai_job_attempt");
		this.jdbcTemplate.update("DELETE FROM ai_job");
		this.jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type = 'AiJob'");
	}

	@Test
	void migrationCreatesCandidateOnlyTables() {
		assertEquals(3, this.jdbcTemplate.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'public'
				 AND table_name IN ('ai_job', 'ai_job_attempt', 'candidate_annotation')
				""", Integer.class));
	}

}
