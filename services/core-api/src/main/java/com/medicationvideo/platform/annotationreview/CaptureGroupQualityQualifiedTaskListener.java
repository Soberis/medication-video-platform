package com.medicationvideo.platform.annotationreview;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQualifiedEvent;

/**
 * Creates or refreshes the single task owned by a CaptureGroup after a qualifying
 * group-quality fact is committed in the current transaction.
 */
@Component
class CaptureGroupQualityQualifiedTaskListener {

	private final CaptureGroupAnnotationWorkflow workflow;

	CaptureGroupQualityQualifiedTaskListener(CaptureGroupAnnotationWorkflow workflow) {
		this.workflow = workflow;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void assign(CaptureGroupQualityQualifiedEvent event) {
		this.workflow.assignAfterQuality(event);
	}

}
