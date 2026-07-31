package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class ExportWorkerSecurityConfigurationTests {

	private static final String EXPORT_WORKER_TOKEN = "synthetic-export-worker-bearer-token-do-not-reuse";

	private static final String WEB_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void rejectsMissingOrWebIdentityWithoutCallingTheBusinessChain() throws Exception {
		assertRejected(null);
		assertRejected("Bearer " + WEB_TOKEN);
	}

	@Test
	void acceptsOnlyTheDedicatedExportWorkerIdentity() throws Exception {
		var filter = new LocalExportWorkerAuthenticationFilter(EXPORT_WORKER_TOKEN);
		var request = request("Bearer " + EXPORT_WORKER_TOKEN);
		var response = new MockHttpServletResponse();
		var invoked = new AtomicBoolean();
		var observedAuthentication = new AtomicReference<Authentication>();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			invoked.set(true);
			observedAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
		});

		assertTrue(invoked.get());
		assertEquals(200, response.getStatus());
		var authentication = observedAuthentication.get();
		assertInstanceOf(ExportWorkerActor.class, authentication.getPrincipal());
		assertEquals("local-export-worker", ((ExportWorkerActor) authentication.getPrincipal()).serviceId());
		assertEquals("ROLE_EXPORT_WORKER", authentication.getAuthorities().iterator().next().getAuthority());
	}

	private static void assertRejected(String authorization) throws Exception {
		var filter = new LocalExportWorkerAuthenticationFilter(EXPORT_WORKER_TOKEN);
		var request = request(authorization);
		var response = new MockHttpServletResponse();
		var invoked = new AtomicBoolean();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

		assertFalse(invoked.get());
		assertEquals(401, response.getStatus());
		assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
		assertEquals("no-store", response.getHeader("Cache-Control"));
		assertEquals("application/problem+json", response.getContentType());
		assertTrue(response.getContentAsString().contains("\"error_code\":\"UNAUTHORIZED\""));
		assertTrue(response.getContentAsString().contains("\"retryable\":false"));
		assertFalse(response.getContentAsString().contains(EXPORT_WORKER_TOKEN));
		assertFalse(response.getContentAsString().contains(WEB_TOKEN));
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}

	private static MockHttpServletRequest request(String authorization) {
		var request = new MockHttpServletRequest("POST",
				"/api/v1/internal/dataset-exports/99999999-9999-9999-9999-999999999999/attempts/1/lease");
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		return request;
	}

}
