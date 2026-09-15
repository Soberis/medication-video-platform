package com.medicationvideo.platform.annotationreview;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.medicationvideo.platform.aicandidate.ActionCandidateJobService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;

class AutomaticActionPreparationSchedulerTests {

	@Test
	void rollsBackFailedDispatchAndContinuesOtherGroups() {
		var project = UUID.randomUUID();
		var first = UUID.randomUUID();
		var second = UUID.randomUUID();
		var quality = mock(CaptureGroupQualityQueryService.class);
		var workflow = mock(CaptureGroupAnnotationWorkflow.class);
		var jobs = mock(ActionCandidateJobService.class);
		var manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any(TransactionDefinition.class)))
			.thenAnswer(call -> new SimpleTransactionStatus());
		when(quality.getCurrentPage(project, 0, 100))
			.thenReturn(List.of(decision(project, first), decision(project, second)));
		when(workflow.prepareAutomatic(eq(project), any(), eq("member"))).thenReturn(true);
		doThrow(new IllegalStateException("outbox unavailable")).when(jobs)
			.queueAutomatically(project, first, "member");
		var scheduler = new AutomaticActionPreparationScheduler(quality, workflow, jobs, manager, project, "member");
		scheduler.reconcile();
		verify(manager).rollback(any());
		verify(manager).commit(any());
		verify(jobs).queueAutomatically(project, second, "member");
	}

	@Test
	void doesNotDispatchUnreadyTask() {
		var project = UUID.randomUUID();
		var group = UUID.randomUUID();
		var quality = mock(CaptureGroupQualityQueryService.class);
		var workflow = mock(CaptureGroupAnnotationWorkflow.class);
		var jobs = mock(ActionCandidateJobService.class);
		var manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
		when(quality.getCurrentPage(project, 0, 100)).thenReturn(List.of(decision(project, group)));
		new AutomaticActionPreparationScheduler(quality, workflow, jobs, manager, project, "member").reconcile();
		verifyNoInteractions(jobs);
	}

	private static CaptureGroupQualityQueryService.CurrentDecision decision(UUID project, UUID group) {
		return new CaptureGroupQualityQueryService.CurrentDecision(UUID.randomUUID(), project, group, "QUALIFIED",
				"synthetic", "member", 1, null, "input", null, Instant.now());
	}

}
