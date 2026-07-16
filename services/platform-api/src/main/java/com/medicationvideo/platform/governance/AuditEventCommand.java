package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventCommand(
		Instant occurredAtUtc,
		AuditActorType actorType,
		String actorId,
		UUID projectId,
		String action,
		String objectType,
		String objectId,
		String objectVersion,
		AuditOutcome outcome,
		String reasonCode,
		String requestId,
		String traceId,
		String authorizationReference,
		Map<String, String> details) {

	public AuditEventCommand {
		details = Map.copyOf(details);
	}
}
