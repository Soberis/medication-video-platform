package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_event")
class AuditEvent {

	@Id
	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "occurred_at_utc", nullable = false, updatable = false)
	private Instant occurredAtUtc;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_type", nullable = false, length = 32, updatable = false)
	private AuditActorType actorType;

	@Column(name = "actor_id", nullable = false, length = 128, updatable = false)
	private String actorId;

	@Column(name = "project_id", updatable = false)
	private UUID projectId;

	@Column(name = "action", nullable = false, length = 128, updatable = false)
	private String action;

	@Column(name = "object_type", nullable = false, length = 64, updatable = false)
	private String objectType;

	@Column(name = "object_id", nullable = false, length = 128, updatable = false)
	private String objectId;

	@Column(name = "object_version", length = 64, updatable = false)
	private String objectVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false, length = 32, updatable = false)
	private AuditOutcome outcome;

	@Column(name = "reason_code", nullable = false, length = 64, updatable = false)
	private String reasonCode;

	@Column(name = "request_id", nullable = false, length = 128, updatable = false)
	private String requestId;

	@Column(name = "trace_id", length = 128, updatable = false)
	private String traceId;

	@Column(name = "authorization_reference", length = 128, updatable = false)
	private String authorizationReference;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "details_json", nullable = false, columnDefinition = "jsonb", updatable = false)
	private Map<String, String> details;

	protected AuditEvent() {
	}

	private AuditEvent(UUID eventId, AuditEventCommand command) {
		this.eventId = eventId;
		this.occurredAtUtc = command.occurredAtUtc();
		this.actorType = command.actorType();
		this.actorId = command.actorId();
		this.projectId = command.projectId();
		this.action = command.action();
		this.objectType = command.objectType();
		this.objectId = command.objectId();
		this.objectVersion = command.objectVersion();
		this.outcome = command.outcome();
		this.reasonCode = command.reasonCode();
		this.requestId = command.requestId();
		this.traceId = command.traceId();
		this.authorizationReference = command.authorizationReference();
		this.details = command.details();
	}

	static AuditEvent from(AuditEventCommand command) {
		return new AuditEvent(UUID.randomUUID(), command);
	}

	UUID getEventId() {
		return this.eventId;
	}

	Instant getOccurredAtUtc() {
		return this.occurredAtUtc;
	}

	AuditActorType getActorType() {
		return this.actorType;
	}

	String getActorId() {
		return this.actorId;
	}

	String getAction() {
		return this.action;
	}

	String getObjectType() {
		return this.objectType;
	}

	String getObjectId() {
		return this.objectId;
	}

	String getObjectVersion() {
		return this.objectVersion;
	}

	AuditOutcome getOutcome() {
		return this.outcome;
	}

	String getReasonCode() {
		return this.reasonCode;
	}

	String getRequestId() {
		return this.requestId;
	}
}
