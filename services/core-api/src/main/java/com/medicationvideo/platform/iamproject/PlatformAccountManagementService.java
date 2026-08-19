package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
class PlatformAccountManagementService {

	private final PlatformAccountRepository repository;

	private final PasswordEncoder passwordEncoder;

	private final AuditEventRecorder auditRecorder;

	PlatformAccountManagementService(PlatformAccountRepository repository, PasswordEncoder passwordEncoder,
			AuditEventRecorder auditRecorder) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	PlatformAccountListResponse list() {
		return new PlatformAccountListResponse(
				this.repository.findAllByOrderByUsernameAsc().stream().map(PlatformAccountResponse::from).toList());
	}

	@Transactional
	PlatformAccountResponse create(String administratorId, String requestId, String traceId,
			PlatformAccountCreateRequest request) {
		var username = request.username().trim();
		if (this.repository.findByUsername(username).isPresent()) {
			throw accountConflict();
		}
		var now = Instant.now();
		try {
			var account = this.repository.saveAndFlush(PlatformAccount.create(username,
					this.passwordEncoder.encode(request.password()), PlatformAccountRole.MEMBER, now));
			recordChanged(account, administratorId, "PLATFORM_ACCOUNT_CREATED", "ACCOUNT_CREATED", requestId, traceId);
			return PlatformAccountResponse.from(account);
		}
		catch (DataIntegrityViolationException exception) {
			throw accountConflict();
		}
	}

	@Transactional
	PlatformAccountResponse changeEnabled(String administratorId, String username, String requestId, String traceId,
			PlatformAccountEnabledRequest request) {
		var account = this.repository.findByUsername(username)
			.orElseThrow(() -> new ProjectApiException("PLATFORM_ACCOUNT_NOT_FOUND",
					"The platform account was not found.", 404));
		if (account.getRole() != PlatformAccountRole.MEMBER) {
			throw new ProjectApiException("PLATFORM_ACCOUNT_ROLE_IMMUTABLE",
					"The bootstrap administrator cannot be changed by this endpoint.", 409);
		}
		var changed = account.isEnabled() != request.enabled();
		account.changeEnabled(request.enabled(), Instant.now());
		account = this.repository.saveAndFlush(account);
		if (changed) {
			recordChanged(account, administratorId,
					request.enabled() ? "PLATFORM_ACCOUNT_ENABLED" : "PLATFORM_ACCOUNT_DISABLED",
					request.enabled() ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED", requestId, traceId);
		}
		return PlatformAccountResponse.from(account);
	}

	private void recordChanged(PlatformAccount account, String administratorId, String action, String reasonCode,
			String requestId, String traceId) {
		this.auditRecorder.record(new AuditEventCommand(account.getUpdatedAt(), AuditActorType.USER, administratorId,
				null, action, "PlatformAccount", account.getAccountId().toString(),
				Long.toString(account.getRowVersion()), AuditOutcome.SUCCEEDED, reasonCode, requestId, traceId,
				"ROLE_ADMIN", Map.of("username", account.getUsername(), "role", account.getRole().name(), "enabled",
						Boolean.toString(account.isEnabled()))));
	}

	private static ProjectApiException accountConflict() {
		return new ProjectApiException("PLATFORM_ACCOUNT_USERNAME_CONFLICT",
				"The platform account username is already in use.", 409);
	}

}
