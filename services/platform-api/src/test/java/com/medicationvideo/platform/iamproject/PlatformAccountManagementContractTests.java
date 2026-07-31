package com.medicationvideo.platform.iamproject;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAccountManagementContractTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlatformAccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void createAdministrator() {
		clearProjectData();
		this.accountRepository.deleteAll();
		this.accountRepository
			.save(PlatformAccount.create("platform-admin", this.passwordEncoder.encode("synthetic-password-123"),
					PlatformAccountRole.ADMIN, Instant.parse("2026-07-21T00:00:00Z")));
	}

	@AfterEach
	void removeAccounts() {
		clearProjectData();
		this.accountRepository.deleteAll();
	}

	@Test
	void administratorCreatesListsAndDisablesMemberAccounts() throws Exception {
		var administrator = login("platform-admin", "synthetic-password-123");

		this.mockMvc
			.perform(post("/api/v1/admin/accounts").session(administrator.session())
				.cookie(administrator.csrfCookie())
				.header("X-XSRF-TOKEN", administrator.csrfCookie().getValue())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"username":"reviewer-01","password":"member-password-123"}
						"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.username").value("reviewer-01"))
			.andExpect(jsonPath("$.role").value("MEMBER"))
			.andExpect(jsonPath("$.enabled").value(true));

		this.mockMvc.perform(get("/api/v1/admin/accounts").session(administrator.session()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[1].username").value("reviewer-01"));

		this.mockMvc
			.perform(patch("/api/v1/admin/accounts/{username}/enabled", "reviewer-01").session(administrator.session())
				.cookie(administrator.csrfCookie())
				.header("X-XSRF-TOKEN", administrator.csrfCookie().getValue())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{" + "\"enabled\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false));

		this.mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"username":"reviewer-01","password":"member-password-123"}
				"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error_code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void memberCannotManageAccountsAndRequestsNeedCsrf() throws Exception {
		this.accountRepository
			.save(PlatformAccount.create("member-01", this.passwordEncoder.encode("member-password-123"),
					PlatformAccountRole.MEMBER, Instant.parse("2026-07-21T00:00:00Z")));
		var member = login("member-01", "member-password-123");
		var administrator = login("platform-admin", "synthetic-password-123");

		this.mockMvc.perform(get("/api/v1/admin/accounts").session(member.session()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PLATFORM_ACCOUNT_ACCESS_DENIED"));

		this.mockMvc
			.perform(post("/api/v1/admin/accounts").session(administrator.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"username":"member-02","password":"member-password-123"}
						"""))
			.andExpect(status().isForbidden());
	}

	@Test
	void administratorAssignsCreatedMemberToProjectAndMemberSeesOnlyThatProject() throws Exception {
		var administrator = login("platform-admin", "synthetic-password-123");
		createMember(administrator, "reviewer-01");
		var projectResponse = this.mockMvc
			.perform(post("/api/v1/projects").session(administrator.session())
				.cookie(administrator.csrfCookie())
				.header("X-XSRF-TOKEN", administrator.csrfCookie().getValue())
				.header("Idempotency-Key", "account-membership-project-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"display_name\":\"Account Membership Study\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var projectId = UUID.fromString(JsonPath.read(projectResponse, "$.project_id"));

		this.mockMvc
			.perform(put("/api/v1/projects/{projectId}/memberships/{actorId}", projectId, "reviewer-01")
				.session(administrator.session())
				.cookie(administrator.csrfCookie())
				.header("X-XSRF-TOKEN", administrator.csrfCookie().getValue())
				.header("Idempotency-Key", "account-membership-grant-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"role\":\"MEMBER\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.actor_id").value("reviewer-01"));

		var member = login("reviewer-01", "member-password-123");
		this.mockMvc.perform(get("/api/v1/projects").session(member.session()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].project_id").value(projectId.toString()))
			.andExpect(jsonPath("$.can_create_projects").value(false));
	}

	private Session login(String username, String password) throws Exception {
		var result = this.mockMvc
			.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
			.andExpect(status().isOk())
			.andReturn();
		return new Session((MockHttpSession) result.getRequest().getSession(false),
				result.getResponse().getCookie("XSRF-TOKEN"));
	}

	private void createMember(Session administrator, String username) throws Exception {
		this.mockMvc
			.perform(post("/api/v1/admin/accounts").session(administrator.session())
				.cookie(administrator.csrfCookie())
				.header("X-XSRF-TOKEN", administrator.csrfCookie().getValue())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"%s\",\"password\":\"member-password-123\"}".formatted(username)))
			.andExpect(status().isCreated());
	}

	private void clearProjectData() {
		this.jdbcTemplate.update("DELETE FROM project_membership_command");
		this.jdbcTemplate.update("DELETE FROM membership");
		this.jdbcTemplate.update("DELETE FROM project");
	}

	private record Session(MockHttpSession session, Cookie csrfCookie) {
	}

}
