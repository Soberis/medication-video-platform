package com.medicationvideo.platform.qualitycleaning;

import java.util.UUID;

/**
 * Public cross-module command with a constrained factory. The owning module keeps reason and
 * source enums private so callers cannot invent unsupported quarantine evidence types.
 */
public final class QuarantineActivationCommand {

	private final UUID projectId;
	private final UUID clipAssetId;
	private final String inputDigest;
	private final QuarantineReasonCode reasonCode;
	private final QuarantineSourceType sourceType;
	private final UUID sourceId;
	private final int sourceVersion;
	private final String actorId;
	private final String requestId;
	private final String traceId;

	private QuarantineActivationCommand(
			UUID projectId,
			UUID clipAssetId,
			String inputDigest,
			QuarantineReasonCode reasonCode,
			QuarantineSourceType sourceType,
			UUID sourceId,
			int sourceVersion,
			String actorId,
			String requestId,
			String traceId) {
		if (projectId == null || clipAssetId == null || sourceId == null) {
			throw new IllegalArgumentException("Project, clip asset and source identifiers are required.");
		}
		if (inputDigest == null || !inputDigest.matches("^[a-f0-9]{64}$")) {
			throw new IllegalArgumentException("inputDigest must be a lowercase SHA-256 value.");
		}
		if (sourceVersion < 1) {
			throw new IllegalArgumentException("sourceVersion must be positive.");
		}
		if (actorId == null || !actorId.matches("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$")) {
			throw new IllegalArgumentException("actorId is invalid.");
		}
		if (requestId == null || !requestId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
			throw new IllegalArgumentException("requestId is invalid.");
		}
		this.projectId = projectId;
		this.clipAssetId = clipAssetId;
		this.inputDigest = inputDigest;
		this.reasonCode = reasonCode;
		this.sourceType = sourceType;
		this.sourceId = sourceId;
		this.sourceVersion = sourceVersion;
		this.actorId = actorId;
		this.requestId = requestId;
		this.traceId = traceId;
	}

	public static QuarantineActivationCommand forPrivacyAssessment(
			UUID projectId,
			UUID clipAssetId,
			String inputDigest,
			UUID assessmentId,
			int assessmentVersion,
			String actorId,
			String requestId,
			String traceId) {
		return new QuarantineActivationCommand(
				projectId,
				clipAssetId,
				inputDigest,
				QuarantineReasonCode.PRIVACY_RISK,
				QuarantineSourceType.PRIVACY_ASSESSMENT,
				assessmentId,
				assessmentVersion,
				actorId,
				requestId,
				traceId);
	}

	UUID projectId() {
		return this.projectId;
	}

	UUID clipAssetId() {
		return this.clipAssetId;
	}

	String inputDigest() {
		return this.inputDigest;
	}

	QuarantineReasonCode reasonCode() {
		return this.reasonCode;
	}

	QuarantineSourceType sourceType() {
		return this.sourceType;
	}

	UUID sourceId() {
		return this.sourceId;
	}

	int sourceVersion() {
		return this.sourceVersion;
	}

	String actorId() {
		return this.actorId;
	}

	String requestId() {
		return this.requestId;
	}

	String traceId() {
		return this.traceId;
	}
}
