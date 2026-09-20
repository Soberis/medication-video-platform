package com.medicationvideo.platform.iamproject;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
class PlatformAuthenticationController {

	private final PlatformAccountAuthenticationService authenticationService;

	private final SecurityContextRepository securityContextRepository;

	PlatformAuthenticationController(PlatformAccountAuthenticationService authenticationService,
			SecurityContextRepository securityContextRepository) {
		this.authenticationService = authenticationService;
		this.securityContextRepository = securityContextRepository;
	}

	@PostMapping("/login")
	ResponseEntity<PlatformSessionResponse> login(@Valid @RequestBody PlatformLoginRequest request,
			@RequestHeader(name = "X-Request-Id", required = false) String requestId, HttpServletRequest servletRequest,
			HttpServletResponse servletResponse, CsrfToken csrfToken) {
		var authentication = this.authenticationService.authenticate(request, resolveRequestId(requestId),
				MDC.get("traceId"));
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		this.securityContextRepository.saveContext(context, servletRequest, servletResponse);
		csrfToken.getToken();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.authenticationService.session(authentication));
	}

	@GetMapping("/session")
	ResponseEntity<PlatformSessionResponse> session(Authentication authentication, CsrfToken csrfToken) {
		var platformAuthentication = requirePlatformAuthentication(authentication);
		csrfToken.getToken();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.authenticationService.session(platformAuthentication));
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		var session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static UsernamePasswordAuthenticationToken requirePlatformAuthentication(Authentication authentication) {
		if (authentication instanceof UsernamePasswordAuthenticationToken platformAuthentication
				&& platformAuthentication.getPrincipal() instanceof PlatformActor) {
			return platformAuthentication;
		}
		throw new PlatformAuthenticationException();
	}

	private static String resolveRequestId(String requestId) {
		return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
	}

}
