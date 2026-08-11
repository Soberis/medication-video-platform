package com.medicationvideo.platform.assetcapture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExternalVideoIngestManifestContractTests {

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
	void acceptsTheExplicitExternalV1ManifestWithoutGuessingCameraIdentity() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/external-video-ingest-manifests", PROJECT_ID)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validManifest()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.schema_version").value("external-video-ingest-v1"))
			.andExpect(jsonPath("$.capture_group_key").value("edge-group-20260811-0001"))
			.andExpect(jsonPath("$.expected_camera_count").value(2))
			.andExpect(jsonPath("$.video_count").value(2));
	}

	@Test
	void rejectsUnknownOrDuplicatedCameraNumbers() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/external-video-ingest-manifests", PROJECT_ID)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validManifest().replace("\"camera_number\": 2", "\"camera_number\": 3")))
			.andExpect(status().isBadRequest());
	}

	private static String validManifest() {
		return """
				{
				  "schema_version": "external-video-ingest-v1",
				  "source_system": "synthetic-edge",
				  "source_device_id": "cabinet-001",
				  "capture_group": {
				    "capture_group_key": "edge-group-20260811-0001",
				    "key_source": "UPSTREAM_MANIFEST_KEY",
				    "captured_at": "2026-08-11T02:00:00Z",
				    "timezone": "Asia/Shanghai",
				    "expected_camera_numbers": [1, 2]
				  },
				  "videos": [
				    {
				      "client_file_id": "00000000-0000-0000-0000-000000000101",
				      "camera_number": 1,
				      "file_name": "camera-1.mp4",
				      "declared_size_bytes": 100,
				      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
				    },
				    {
				      "client_file_id": "00000000-0000-0000-0000-000000000102",
				      "camera_number": 2,
				      "file_name": "camera-2.mp4",
				      "declared_size_bytes": 100,
				      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
				    }
				  ]
				}
				""";
	}

}
