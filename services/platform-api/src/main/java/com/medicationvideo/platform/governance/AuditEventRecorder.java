package com.medicationvideo.platform.governance;

import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class AuditEventRecorder {

	private final AuditEventRepository repository;

	AuditEventRecorder(AuditEventRepository repository) {
		this.repository = repository;
	}

	public UUID record(AuditEventCommand command) {
		return this.repository.save(AuditEvent.from(command)).getEventId();
	}
}
