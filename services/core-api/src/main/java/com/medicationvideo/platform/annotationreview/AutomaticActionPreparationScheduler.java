package com.medicationvideo.platform.annotationreview;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.medicationvideo.platform.aicandidate.ActionCandidateJobService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;

/** Bounded reconciliation also recovers preparation after process restarts. */
@Component
@ConditionalOnProperty(name = "platform.ai.action.auto.enabled", havingValue = "true")
class AutomaticActionPreparationScheduler {

	private static final Logger LOG = LoggerFactory.getLogger(AutomaticActionPreparationScheduler.class);

	private final CaptureGroupQualityQueryService quality;

	private final CaptureGroupAnnotationWorkflow workflow;

	private final ActionCandidateJobService jobs;

	private final TransactionTemplate transaction;

	private final UUID projectId;

	private final String assignee;

	private int page;

	AutomaticActionPreparationScheduler(CaptureGroupQualityQueryService quality,
			CaptureGroupAnnotationWorkflow workflow, ActionCandidateJobService jobs,
			PlatformTransactionManager transactionManager,
			@Value("${platform.ai.action.auto.project-id}") UUID projectId,
			@Value("${platform.ai.action.auto.assignee}") String assignee) {
		if (assignee == null || assignee.isBlank())
			throw new IllegalArgumentException("Automatic action assignee is required");
		this.quality = quality;
		this.workflow = workflow;
		this.jobs = jobs;
		this.transaction = new TransactionTemplate(transactionManager);
		this.projectId = projectId;
		this.assignee = assignee.trim();
	}

	@Scheduled(fixedDelayString = "${platform.ai.action.auto.poll-delay:PT10S}")
	void reconcile() {
		var records = this.quality.getCurrentPage(this.projectId, this.page, 100);
		this.page = records.size() < 100 ? 0 : this.page + 1;
		for (var record : records) {
			if (!record.isAdmitting())
				continue;
			try {
				this.transaction.executeWithoutResult(status -> {
					if (this.workflow.prepareAutomatic(this.projectId, record.captureGroupId(), this.assignee)) {
						this.jobs.queueAutomatically(this.projectId, record.captureGroupId(), this.assignee);
					}
				});
			}
			catch (RuntimeException exception) {
				LOG.warn("Automatic action preparation failed for group {}; will recheck", record.captureGroupId(),
						exception);
			}
		}
	}

}
