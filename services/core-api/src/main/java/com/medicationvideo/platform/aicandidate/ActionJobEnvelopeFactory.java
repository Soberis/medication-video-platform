package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;

/**
 * Builds the versioned action job envelope. Both the initial dispatch and the
 * lease-recovery retry path go through this component so a retried action job is
 * published with the same contract as the first attempt.
 */
@Component
class ActionJobEnvelopeFactory {

	private static final String SCHEMA_VERSION = "ai-action-job.v1";

	private static final List<String> FALLBACK_LABELS = List.of("acquire_item", "open_container", "disinfect_access",
			"insert_needle", "draw_liquid", "transfer_liquid", "agitate_solution", "discard_sharps",
			"label_prepared_medication");

	private final AssetCatalogQueryService assetCatalog;

	private final ActionAllowedLabelsPort labelsPort;

	@Autowired
	ActionJobEnvelopeFactory(@Autowired(required = false) AssetCatalogQueryService assetCatalog,
			@Autowired(required = false) ActionAllowedLabelsPort labelsPort) {
		this.assetCatalog = assetCatalog;
		this.labelsPort = labelsPort;
	}

	UUID resolveCaptureGroupId(UUID clipAssetId) {
		return this.assetCatalog != null ? this.assetCatalog.resolveCaptureGroupId(clipAssetId) : null;
	}

	List<String> resolveAllowedLabels() {
		if (this.labelsPort != null) {
			try {
				var labels = this.labelsPort.resolveAllowedLabels();
				if (labels != null && !labels.isEmpty()) {
					return labels;
				}
			}
			catch (RuntimeException ignored) {
				// Fall through to the frozen P0 label set when the active schema is
				// unavailable.
			}
		}
		return FALLBACK_LABELS;
	}

	AiActionJobEnvelope create(AiCandidateJob job, UUID captureGroupId, int attempt, UUID messageId, String leaseToken,
			Instant requestedAt) {
		return new AiActionJobEnvelope(SCHEMA_VERSION, messageId, job.getAiJobId(), attempt, leaseToken,
				job.getProjectId(), captureGroupId, job.getClipAssetId(), job.getInputSha256(), job.getInputSizeBytes(),
				job.getContentType(), job.getAlgorithmKey(), job.getAlgorithmVersion(), job.getModelSha256(),
				job.getSamplingFps(), job.getProfileSha256(), resolveAllowedLabels(), requestedAt);
	}

}
