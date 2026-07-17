package com.medicationvideo.platform.qualitycleaning;

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
@Table(name = "quarantine_record")
class QuarantineRecord {

	@Id
	@Column(name = "quarantine_record_id", nullable = false, updatable = false)
	private UUID quarantineRecordId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "input_digest", nullable = false, length = 64, updatable = false)
	private String inputDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, length = 64, updatable = false)
	private QuarantineReasonCode reasonCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, length = 64, updatable = false)
	private QuarantineSourceType sourceType;

	@Column(name = "source_id", nullable = false, updatable = false)
	private UUID sourceId;

	@Column(name = "source_version", nullable = false, updatable = false)
	private int sourceVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private QuarantineStatus status;

	@Column(name = "activated_by", nullable = false, length = 128, updatable = false)
	private String activatedBy;

	@Column(name = "activated_at", nullable = false, updatable = false)
	private Instant activatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long rowVersion;

	protected QuarantineRecord() {
	}

	private QuarantineRecord(QuarantineActivationCommand command, Instant activatedAt) {
		this.quarantineRecordId = UUID.randomUUID();
		this.projectId = command.projectId();
		this.clipAssetId = command.clipAssetId();
		this.inputDigest = command.inputDigest();
		this.reasonCode = command.reasonCode();
		this.sourceType = command.sourceType();
		this.sourceId = command.sourceId();
		this.sourceVersion = command.sourceVersion();
		this.status = QuarantineStatus.ACTIVE;
		this.activatedBy = command.actorId();
		this.activatedAt = activatedAt;
	}

	static QuarantineRecord activate(QuarantineActivationCommand command, Instant activatedAt) {
		return new QuarantineRecord(command, activatedAt);
	}

	boolean matches(QuarantineActivationCommand command) {
		return this.projectId.equals(command.projectId())
				&& this.clipAssetId.equals(command.clipAssetId())
				&& this.inputDigest.equals(command.inputDigest())
				&& this.reasonCode == command.reasonCode()
				&& this.sourceType == command.sourceType()
				&& this.sourceId.equals(command.sourceId())
				&& this.sourceVersion == command.sourceVersion();
	}

	UUID getQuarantineRecordId() {
		return this.quarantineRecordId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	String getInputDigest() {
		return this.inputDigest;
	}

	QuarantineReasonCode getReasonCode() {
		return this.reasonCode;
	}

	QuarantineSourceType getSourceType() {
		return this.sourceType;
	}

	UUID getSourceId() {
		return this.sourceId;
	}

	int getSourceVersion() {
		return this.sourceVersion;
	}

	QuarantineStatus getStatus() {
		return this.status;
	}

	String getActivatedBy() {
		return this.activatedBy;
	}

	Instant getActivatedAt() {
		return this.activatedAt;
	}
}
