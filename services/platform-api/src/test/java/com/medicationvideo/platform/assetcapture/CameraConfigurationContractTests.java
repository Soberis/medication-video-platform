package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CameraConfigurationContractTests {

	private static final String TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";
	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CameraChannelRepository channelRepository;

	@Autowired
	private CameraSetConfigurationRepository configurationRepository;

	@Autowired
	private CameraSetMemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearCameraConfiguration() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		this.memberRepository.deleteAll();
		this.configurationRepository.deleteAll();
		this.channelRepository.deleteAll();
	}

	@AfterEach
	void clearAdditionalProject() {
		this.memberRepository.deleteAll();
		this.configurationRepository.deleteAll();
		this.channelRepository.deleteAll();
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", OTHER_PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", OTHER_PROJECT_ID);
	}

	@Test
	void provisionsAStableLogicalChannelIdempotently() throws Exception {
		var first = provisionChannel(PROJECT_ID, "cabinet.overview-01")
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.camera_channel_id").isNotEmpty())
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
				.andExpect(jsonPath("$.provisioning_key").value("cabinet.overview-01"))
				.andExpect(jsonPath("$.created_at").isNotEmpty())
				.andReturn();

		var replay = provisionChannel(PROJECT_ID, "cabinet.overview-01")
				.andExpect(status().isCreated())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.channelRepository.count());
	}

	@Test
	void createsAnImmutableCameraSetVersionIndependentOfMemberOrder() throws Exception {
		var overviewId = provisionedChannelId("cabinet.overview-02");
		var handsId = provisionedChannelId("cabinet.hands-01");
		var request = cameraSetRequest(overviewId, handsId, "layout-2026-01");

		var first = configureCameraSet("cabinet-a", 1, request)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.camera_set_id").isNotEmpty())
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
				.andExpect(jsonPath("$.camera_set_key").value("cabinet-a"))
				.andExpect(jsonPath("$.configuration_version").value(1))
				.andExpect(jsonPath("$.layout_version").value("layout-2026-01"))
				.andExpect(jsonPath("$.enabled_camera_count").value(2))
				.andExpect(jsonPath("$.members.length()").value(2))
				.andExpect(jsonPath("$.members[0].camera_index").value(0))
				.andExpect(jsonPath("$.members[0].camera_role").value("OVERVIEW"))
				.andExpect(jsonPath("$.members[1].camera_index").value(1))
				.andExpect(jsonPath("$.members[1].camera_role").value("HANDS"))
				.andReturn();

		var reordered = reorderedCameraSetRequest(overviewId, handsId, "layout-2026-01");
		var replay = configureCameraSet("cabinet-a", 1, reordered)
				.andExpect(status().isCreated())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.configurationRepository.count());
		assertEquals(2, this.memberRepository.count());
	}

	@Test
	void rejectsChangedContentForAnExistingVersion() throws Exception {
		var channelId = provisionedChannelId("cabinet.overview-03");
		configureCameraSet("cabinet-b", 1, singleMemberRequest(channelId, "layout-1", true))
				.andExpect(status().isCreated());

		configureCameraSet("cabinet-b", 1, singleMemberRequest(channelId, "layout-2", true))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.error_code").value("CAMERA_SET_VERSION_CONFLICT"));
	}

	@Test
	void enforcesMemberUniquenessAndAnEnabledChannel() throws Exception {
		var firstId = provisionedChannelId("cabinet.overview-04");
		var secondId = provisionedChannelId("cabinet.hands-02");

		configureCameraSet("cabinet-c", 1, duplicateIndexRequest(firstId, secondId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAMERA_SET_DUPLICATE_INDEX"));

		configureCameraSet("cabinet-c", 2, duplicateChannelRequest(firstId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAMERA_SET_DUPLICATE_CHANNEL"));

		configureCameraSet("cabinet-c", 3, singleMemberRequest(firstId, "layout-disabled", false))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAMERA_SET_NO_ENABLED_CHANNEL"));
	}

	@Test
	void enforcesProjectScopeForConfigurationAndMembers() throws Exception {
		provisionChannel(OTHER_PROJECT_ID, "other-project-channel")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));

		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, OTHER_PROJECT_ID);
		provisionChannel(OTHER_PROJECT_ID, "other-authorized-channel")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.project_id").value(OTHER_PROJECT_ID.toString()));

		this.mockMvc.perform(put(
				"/api/v1/projects/{projectId}/camera-channels/{provisioningKey}",
				PROJECT_ID,
				"member-denied-channel")
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var otherChannel = this.channelRepository.saveAndFlush(
				CameraChannel.provision(OTHER_PROJECT_ID, "other-project-channel", now));
		configureCameraSet(
				"cabinet-d",
				1,
				singleMemberRequest(otherChannel.getCameraChannelId(), "layout-foreign", true))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAMERA_SET_CHANNEL_NOT_FOUND"));
	}

	@Test
	void appliesCameraConfigurationMigrationAndUniquenessConstraints() {
		var migrationCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success = true",
				Integer.class);
		var constraintCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE constraint_name IN "
						+ "('uk_camera_channel_project_key', 'uk_camera_set_project_key_version', "
						+ "'uk_camera_set_member_channel', 'uk_camera_set_member_index')",
				Integer.class);

		assertEquals(1, migrationCount);
		assertEquals(4, constraintCount);
	}

	private UUID provisionedChannelId(String provisioningKey) throws Exception {
		provisionChannel(PROJECT_ID, provisioningKey).andExpect(status().isCreated());
		return this.channelRepository.findByProjectIdAndProvisioningKey(PROJECT_ID, provisioningKey)
				.orElseThrow()
				.getCameraChannelId();
	}

	private org.springframework.test.web.servlet.ResultActions provisionChannel(
			UUID projectId,
			String provisioningKey) throws Exception {
		return this.mockMvc.perform(put(
				"/api/v1/projects/{projectId}/camera-channels/{provisioningKey}",
				projectId,
				provisioningKey)
				.header("Authorization", "Bearer " + TOKEN));
	}

	private org.springframework.test.web.servlet.ResultActions configureCameraSet(
			String cameraSetKey,
			int configurationVersion,
			String request) throws Exception {
		return this.mockMvc.perform(put(
				"/api/v1/projects/{projectId}/camera-sets/{cameraSetKey}/versions/{configurationVersion}",
				PROJECT_ID,
				cameraSetKey,
				configurationVersion)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request));
	}

	private static String cameraSetRequest(UUID overviewId, UUID handsId, String layoutVersion) {
		return """
				{
				  "layout_version": "%s",
				  "members": [
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 0,
				      "camera_role": "OVERVIEW",
				      "mount_position": "front-center",
				      "enabled": true
				    },
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 1,
				      "camera_role": "HANDS",
				      "mount_position": "top-down",
				      "enabled": true
				    }
				  ]
				}
				""".formatted(layoutVersion, overviewId, handsId);
	}

	private static String reorderedCameraSetRequest(UUID overviewId, UUID handsId, String layoutVersion) {
		return """
				{
				  "layout_version": "%s",
				  "members": [
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 1,
				      "camera_role": "HANDS",
				      "mount_position": "top-down",
				      "enabled": true
				    },
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 0,
				      "camera_role": "OVERVIEW",
				      "mount_position": "front-center",
				      "enabled": true
				    }
				  ]
				}
				""".formatted(layoutVersion, handsId, overviewId);
	}

	private static String singleMemberRequest(UUID channelId, String layoutVersion, boolean enabled) {
		return """
				{
				  "layout_version": "%s",
				  "members": [
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 0,
				      "camera_role": "OVERVIEW",
				      "mount_position": "front-center",
				      "enabled": %s
				    }
				  ]
				}
				""".formatted(layoutVersion, channelId, enabled);
	}

	private static String duplicateIndexRequest(UUID firstId, UUID secondId) {
		return """
				{
				  "layout_version": "layout-duplicate-index",
				  "members": [
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 0,
				      "camera_role": "OVERVIEW",
				      "mount_position": "front-center",
				      "enabled": true
				    },
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 0,
				      "camera_role": "HANDS",
				      "mount_position": "top-down",
				      "enabled": true
				    }
				  ]
				}
				""".formatted(firstId, secondId);
	}

	private static String duplicateChannelRequest(UUID channelId) {
		return """
				{
				  "layout_version": "layout-duplicate-channel",
				  "members": [
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 0,
				      "camera_role": "OVERVIEW",
				      "mount_position": "front-center",
				      "enabled": true
				    },
				    {
				      "camera_channel_id": "%s",
				      "camera_index": 1,
				      "camera_role": "HANDS",
				      "mount_position": "top-down",
				      "enabled": true
				    }
				  ]
				}
				""".formatted(channelId, channelId);
	}
}
