package com.medicationvideo.platform.annotationreview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background scheduler for V1.36 optimistic auto-approval.
 *
 * <p>
 * The periodic reconciliation is intentionally kept in a separate bean from
 * {@link AutomaticOptimisticApprovalService} so that the service itself stays available
 * for direct (event-driven, programmatic and test) invocation while the fixed-delay
 * schedule can be switched off with
 * {@code platform.annotation.auto-approval.enabled=false}. Disabling the property removes
 * this scheduler entirely, preventing background adoption from racing with test fixtures
 * and data teardown.
 */
@Component
@ConditionalOnProperty(name = "platform.annotation.auto-approval.enabled", havingValue = "true", matchIfMissing = true)
public class AutomaticOptimisticApprovalScheduler {

	private final AutomaticOptimisticApprovalService service;

	AutomaticOptimisticApprovalScheduler(AutomaticOptimisticApprovalService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${platform.annotation.auto-approval.poll-delay:PT5S}")
	public void reconcile() {
		this.service.reconcile();
	}

}
