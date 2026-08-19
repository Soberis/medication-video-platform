package com.medicationvideo.platform.iamproject;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAuthenticationContractTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlatformAccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void createAccount() {
		this.accountRepository.deleteAll();
		this.accountRepository
			.save(PlatformAccount.create("platform-admin", this.passwordEncoder.encode("synthetic-password-123"),
					PlatformAccountRole.ADMIN, Instant.parse("2026-07-21T00:00:00Z")));
	}

	@AfterEach
	void removeAccounts() {
		this.accountRepository.deleteAll();
	}

	@Test
	void loginCreatesAnHttpOnlySessionAndCsrfProtectedLogout() throws Exception {
		var login = this.mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"username":"platform-admin","password":"synthetic-password-123"}
				"""))
			.andExpect(status().isOk())
			.andExpect(cookie().httpOnly("XSRF-TOKEN", false))
			.andExpect(jsonPath("$.username").value("platform-admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andReturn();

		var session = (MockHttpSession) login.getRequest().getSession(false);
		var csrfCookie = login.getResponse().getCookie("XSRF-TOKEN");
		this.mockMvc.perform(get("/api/v1/auth/session").session(session).cookie(csrfCookie))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("platform-admin"));

		this.mockMvc
			.perform(post("/api/v1/auth/logout").session(session)
				.cookie(csrfCookie)
				.header("X-XSRF-TOKEN", csrfCookie.getValue()))
			.andExpect(status().isNoContent());
		this.mockMvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void loginUsesOneSafeMessageForUnknownAccountsAndWrongPasswords() throws Exception {
		this.mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"username":"missing-user","password":"synthetic-password-123"}
				"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error_code").value("INVALID_CREDENTIALS"));

		this.mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"username":"platform-admin","password":"incorrect-password-123"}
				"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error_code").value("INVALID_CREDENTIALS"));
	}

}
