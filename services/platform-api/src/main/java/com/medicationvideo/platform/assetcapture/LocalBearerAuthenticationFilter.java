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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class LocalBearerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final byte[] expectedToken;
	private final UUID projectId;

	LocalBearerAuthenticationFilter(String expectedToken, UUID projectId) {
		this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
		this.projectId = projectId;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/v1/");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		var authorization = request.getHeader("Authorization");
		if (authorization == null
				|| !authorization.startsWith(BEARER_PREFIX)
				|| this.expectedToken.length == 0
				|| !MessageDigest.isEqual(
						authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8),
						this.expectedToken)) {
			unauthorized(response);
			return;
		}

		var actor = new UploadActor(this.projectId, "local-synthetic-uploader");
		var authentication = new UsernamePasswordAuthenticationToken(
				actor,
				null,
				List.of(new SimpleGrantedAuthority("ROLE_UPLOADER")));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		filterChain.doFilter(request, response);
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader("WWW-Authenticate", "Bearer");
		response.setContentType("application/problem+json");
		response.getWriter().write("{\"error_code\":\"UNAUTHORIZED\","
				+ "\"error_message\":\"A valid bearer token is required.\","
				+ "\"retryable\":false,"
				+ "\"request_id\":\"" + UUID.randomUUID() + "\"}");
	}
}
