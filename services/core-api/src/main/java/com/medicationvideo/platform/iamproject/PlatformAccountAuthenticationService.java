package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
class PlatformAccountAuthenticationService {

	private final PlatformAccountRepository repository;

	private final PasswordEncoder passwordEncoder;

	private final AuditEventRecorder auditRecorder;

	private final UUID legacyDefaultProjectId;

	PlatformAccountAuthenticationService(PlatformAccountRepository repository, PasswordEncoder passwordEncoder,
			AuditEventRecorder auditRecorder,
			@Value("${platform.security.local-project-id}") UUID legacyDefaultProjectId) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
		this.auditRecorder = auditRecorder;
		this.legacyDefaultProjectId = legacyDefaultProjectId;
	}

	UsernamePasswordAuthenticationToken authenticate(PlatformLoginRequest request, String requestId, String traceId) {
		var account = this.repository.findByUsername(request.username())
			.filter(PlatformAccount::isEnabled)
			.filter(candidate -> this.passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
			.orElseThrow(() -> denied(requestId, traceId));
		var actor = new PlatformActor(this.legacyDefaultProjectId, account.getUsername());
		var authentication = new UsernamePasswordAuthenticationToken(actor, null,
				List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
		recordLogin(account, AuditOutcome.SUCCEEDED, "PASSWORD_AUTHENTICATED", requestId, traceId);
		return authentication;
	}

	PlatformSessionResponse session(UsernamePasswordAuthenticationToken authentication) {
		var role = authentication.getAuthorities()
			.stream()
			.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())) ? PlatformAccountRole.ADMIN
					: PlatformAccountRole.MEMBER;
		var actor = (PlatformActor) authentication.getPrincipal();
		return new PlatformSessionResponse(actor.actorId(), role);
	}

	private PlatformAuthenticationException denied(String requestId, String traceId) {
		this.auditRecorder.record(new AuditEventCommand(Instant.now(), AuditActorType.SYSTEM, "anonymous", null,
				"USER_LOGIN", "PlatformAccount", "unknown", null, AuditOutcome.DENIED, "INVALID_CREDENTIALS", requestId,
				traceId, "PASSWORD", java.util.Map.of()));
		return new PlatformAuthenticationException();
	}

	private void recordLogin(PlatformAccount account, AuditOutcome outcome, String reasonCode, String requestId,
			String traceId) {
		this.auditRecorder.record(new AuditEventCommand(Instant.now(), AuditActorType.USER, account.getUsername(), null,
				"USER_LOGIN", "PlatformAccount", account.getAccountId().toString(), null, outcome, reasonCode,
				requestId, traceId, "PASSWORD", java.util.Map.of()));
	}

}
