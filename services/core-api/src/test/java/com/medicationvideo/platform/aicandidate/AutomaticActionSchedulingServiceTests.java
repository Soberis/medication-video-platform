package com.medicationvideo.platform.aicandidate;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService.ActionProcessingInput;

class AutomaticActionSchedulingServiceTests {

	@Test
	void processesDifferentProjectsWithoutAssigneesAndContinuesAfterOneFailure() {
		var assets = mock(AssetCatalogQueryService.class);
		var jobs = mock(ActionCandidateJobService.class);
		var first = new ActionProcessingInput(UUID.randomUUID(), UUID.randomUUID());
		var second = new ActionProcessingInput(UUID.randomUUID(), UUID.randomUUID());
		when(assets.findActionProcessingInputs(0, 100)).thenReturn(List.of(first, second));
		doThrow(new IllegalStateException("synthetic outbox failure")).when(jobs)
			.queueAutomatically(first.projectId(), first.clipAssetId());
		var scheduler = new AutomaticActionSchedulingService(assets, jobs);
		scheduler.reconcile();
		scheduler.reconcile();
		verify(jobs, times(2)).queueAutomatically(first.projectId(), first.clipAssetId());
		verify(jobs, times(2)).queueAutomatically(second.projectId(), second.clipAssetId());
	}

	@Test
	void pagesPastFullBatchAndWrapsAtEnd() {
		var assets = mock(AssetCatalogQueryService.class);
		var jobs = mock(ActionCandidateJobService.class);
		var input = new ActionProcessingInput(UUID.randomUUID(), UUID.randomUUID());
		when(assets.findActionProcessingInputs(0, 100)).thenReturn(java.util.Collections.nCopies(100, input));
		when(assets.findActionProcessingInputs(1, 100)).thenReturn(List.of());
		var scheduler = new AutomaticActionSchedulingService(assets, jobs);
		scheduler.reconcile();
		scheduler.reconcile();
		scheduler.reconcile();
		verify(assets, times(2)).findActionProcessingInputs(0, 100);
		verify(assets).findActionProcessingInputs(1, 100);
	}

}
