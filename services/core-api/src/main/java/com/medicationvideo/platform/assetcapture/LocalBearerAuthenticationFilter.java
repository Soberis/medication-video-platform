package com.medicationvideo.platform.assetcapture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class LocalBearerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final byte[] expectedUploaderToken;

	private final byte[] expectedAdminToken;

	private final UUID projectId;

	LocalBearerAuthenticationFilter(String expectedUploaderToken, String expectedAdminToken, UUID projectId) {
		if (!expectedAdminToken.isEmpty() && expectedAdminToken.equals(expectedUploaderToken)) {
			throw new IllegalArgumentException("Local uploader and administrator bearer tokens must be different.");
		}
		this.expectedUploaderToken = expectedUploaderToken.getBytes(StandardCharsets.UTF_8);
		this.expectedAdminToken = expectedAdminToken.getBytes(StandardCharsets.UTF_8);
		this.projectId = projectId;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		var requestUri = request.getRequestURI();
		return !requestUri.startsWith("/api/v1/") || requestUri.startsWith("/api/v1/internal/media-jobs/")
				|| requestUri.startsWith("/api/v1/internal/capture-sync-jobs/")
				|| requestUri.startsWith("/api/v1/internal/ai-jobs/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		var authorization = request.getHeader("Authorization");
		if (authorization == null || authorization.isBlank()) {
			filterChain.doFilter(request, response);
			return;
		}
		if (!authorization.startsWith(BEARER_PREFIX)) {
			unauthorized(response);
			return;
		}

		var presentedToken = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
		var admin = this.expectedAdminToken.length > 0
				&& MessageDigest.isEqual(presentedToken, this.expectedAdminToken);
		var uploader = this.expectedUploaderToken.length > 0
				&& MessageDigest.isEqual(presentedToken, this.expectedUploaderToken);
		if (!admin && !uploader) {
			unauthorized(response);
			return;
		}

		var actor = new PlatformActor(this.projectId, admin ? "local-synthetic-admin" : "local-synthetic-uploader");
		var authentication = new UsernamePasswordAuthenticationToken(actor, null,
				List.of(new SimpleGrantedAuthority(admin ? "ROLE_ADMIN" : "ROLE_MEMBER")));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		filterChain.doFilter(request, response);
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader("WWW-Authenticate", "Bearer");
		response.setContentType("application/problem+json");
		response.getWriter()
			.write("{\"error_code\":\"UNAUTHORIZED\"," + "\"error_message\":\"A valid bearer token is required.\","
					+ "\"retryable\":false," + "\"request_id\":\"" + UUID.randomUUID() + "\"}");
	}

}
