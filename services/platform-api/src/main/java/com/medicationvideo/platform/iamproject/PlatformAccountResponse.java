package com.medicationvideo.platform.iamproject;

import java.time.Instant;

record PlatformAccountResponse(String username, PlatformAccountRole role, boolean enabled, long version,
		Instant createdAt, Instant updatedAt) {

	static PlatformAccountResponse from(PlatformAccount account) {
		return new PlatformAccountResponse(account.getUsername(), account.getRole(), account.isEnabled(),
				account.getRowVersion(), account.getCreatedAt(), account.getUpdatedAt());
	}
}
