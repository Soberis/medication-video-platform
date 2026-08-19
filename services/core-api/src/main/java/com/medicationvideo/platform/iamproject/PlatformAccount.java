package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "platform_account")
class PlatformAccount {

	@Id
	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(name = "username", nullable = false, length = 128, updatable = false)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 32)
	private PlatformAccountRole role;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long rowVersion;

	protected PlatformAccount() {
	}

	private PlatformAccount(UUID accountId, String username, String passwordHash, PlatformAccountRole role,
			Instant now) {
		this.accountId = accountId;
		this.username = username;
		this.passwordHash = passwordHash;
		this.role = role;
		this.enabled = true;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static PlatformAccount create(String username, String passwordHash, PlatformAccountRole role, Instant now) {
		return new PlatformAccount(UUID.randomUUID(), username, passwordHash, role, now);
	}

	UUID getAccountId() {
		return this.accountId;
	}

	String getUsername() {
		return this.username;
	}

	String getPasswordHash() {
		return this.passwordHash;
	}

	PlatformAccountRole getRole() {
		return this.role;
	}

	boolean isEnabled() {
		return this.enabled;
	}

	void changeEnabled(boolean enabled, Instant now) {
		if (this.enabled == enabled) {
			return;
		}
		this.enabled = enabled;
		this.updatedAt = now;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

	long getRowVersion() {
		return this.rowVersion;
	}

}
