package com.medicationvideo.platform.qualitycleaning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;

class QuarantineExceptionHandlerTests {

	private static final String REQUEST_ID = "quarantine-handler-request-0001";

	private final QuarantineExceptionHandler handler = new QuarantineExceptionHandler();

	@BeforeEach
	void setRequestContext() {
		var request = new MockHttpServletRequest();
		request.addHeader("X-Request-Id", REQUEST_ID);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	@AfterEach
	void clearRequestContext() {
		RequestContextHolder.resetRequestAttributes();
		MDC.clear();
	}

	@Test
	void mapsUnknownAssetCatalogErrorsToStableInternalError() {
		var response = this.handler.assetCatalog(new AssetCatalogQueryException(
				"UNRECOGNIZED_ASSET_CATALOG_ERROR",
				"An internal asset catalog detail that must not escape."));

		assertStableInternalError(response);
	}

	@Test
	void mapsUnexpectedErrorsToStableInternalError() {
		var response = this.handler.unexpected(
				new IllegalStateException("An internal detail that must not escape."));

		assertStableInternalError(response);
	}

	private static void assertStableInternalError(
			ResponseEntity<QuarantineApiErrorResponse> response) {
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
		assertEquals("no-store", response.getHeaders().getCacheControl());

		var body = response.getBody();
		assertNotNull(body);
		assertEquals("INTERNAL_ERROR", body.errorCode());
		assertEquals(
				"The quarantine service encountered an unexpected error.",
				body.errorMessage());
		assertTrue(body.retryable());
		assertEquals(REQUEST_ID, body.requestId());
		assertNull(body.traceId());
	}
}
