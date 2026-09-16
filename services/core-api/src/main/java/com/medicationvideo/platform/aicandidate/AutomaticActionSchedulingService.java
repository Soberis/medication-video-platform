package com.medicationvideo.platform.aicandidate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;

/** Bounded reconciliation survives restarts; each clip dispatch owns its transaction. */
@Component
@ConditionalOnProperty(name = "platform.ai.action.auto.enabled", havingValue = "true", matchIfMissing = true)
class AutomaticActionSchedulingService {

	private static final Logger LOG = LoggerFactory.getLogger(AutomaticActionSchedulingService.class);

	private final AssetCatalogQueryService assets;

	private final ActionCandidateJobService jobs;

	private int page;

	AutomaticActionSchedulingService(AssetCatalogQueryService assets, ActionCandidateJobService jobs) {
		this.assets = assets;
		this.jobs = jobs;
	}

	@Scheduled(fixedDelayString = "${platform.ai.action.auto.poll-delay:PT10S}")
	void reconcile() {
		var inputs = this.assets.findActionProcessingInputs(this.page, 100);
		this.page = inputs.size() < 100 ? 0 : this.page + 1;
		for (var input : inputs) {
			try {
				this.jobs.queueAutomatically(input.projectId(), input.clipAssetId());
			}
			catch (RuntimeException exception) {
				LOG.warn("Automatic action scheduling deferred for clip {}: {}", input.clipAssetId(),
						exception.getClass().getSimpleName());
			}
		}
	}

}
