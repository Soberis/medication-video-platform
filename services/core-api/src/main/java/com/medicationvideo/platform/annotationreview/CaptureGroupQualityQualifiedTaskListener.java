package com.medicationvideo.platform.annotationreview;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQualifiedEvent;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;

/**
 * Creates or refreshes the single task owned by a CaptureGroup after a qualifying
 * group-quality fact is committed in the current transaction.
 */
@Component
class CaptureGroupQualityQualifiedTaskListener {

	private final CaptureGroupQualityQueryService groupQualityQueryService;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final AnnotationTaskRepository taskRepository;

	private final AuditEventRecorder auditRecorder;

	CaptureGroupQualityQualifiedTaskListener(CaptureGroupQualityQueryService groupQualityQueryService,
			AnnotationLabelSchemaService labelSchemaService, AnnotationTaskRepository taskRepository,
			AuditEventRecorder auditRecorder) {
		this.groupQualityQueryService = groupQualityQueryService;
		this.labelSchemaService = labelSchemaService;
		this.taskRepository = taskRepository;
		this.auditRecorder = auditRecorder;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void assign(CaptureGroupQualityQualifiedEvent event) {
		var current = this.groupQualityQueryService.lockAndGetCurrentDecision(event.projectId(),
				event.captureGroupId());
		if (!current.isAdmitting() || !event.captureGroupQualityReviewId().equals(current.captureGroupQualityReviewId())
				|| event.reviewRevision() != current.revision()) {
			return;
		}

		var existing = this.taskRepository.findByProjectIdAndCaptureGroupId(event.projectId(), event.captureGroupId());
		if (existing.isPresent()) {
			var task = existing.get();
			task.refreshGroupQualitySnapshot(current.captureGroupQualityReviewId(), current.revision(),
					current.confirmedViewSet(), current.inputVersion(), event.triggeredAt());
			this.taskRepository.saveAndFlush(task);
			return;
		}

		var task = this.taskRepository.saveAndFlush(
				AnnotationTask.createForCaptureGroup(event.projectId(), event.captureGroupId(), event.reviewerActorId(),
						event.reviewerActorId(), "auto-group-quality-" + event.captureGroupQualityReviewId(),
						this.labelSchemaService.currentBinding(), current.captureGroupQualityReviewId(),
						current.revision(), current.confirmedViewSet(), current.inputVersion(), event.triggeredAt()));
		this.auditRecorder.record(new AuditEventCommand(event.triggeredAt(), AuditActorType.USER,
				event.reviewerActorId(), event.projectId(), "CAPTURE_GROUP_ANNOTATION_TASK_AUTO_ASSIGNED",
				"AnnotationTask", task.getAnnotationTaskId().toString(), Long.toString(task.getVersion()),
				AuditOutcome.SUCCEEDED, "AUTO_AFTER_GROUP_QUALITY_QUALIFIED", event.requestId(), event.traceId(),
				"PROJECT_MEMBERSHIP_" + event.reviewerRole().name(),
				Map.of("capture_group_id", event.captureGroupId().toString(), "group_quality_review_id",
						event.captureGroupQualityReviewId().toString(), "group_quality_revision",
						Long.toString(event.reviewRevision()), "assignee_actor_id", task.getAssigneeActorId(),
						"created_by", task.getCreatedBy(), "input_version", current.inputVersion(), "assignment_mode",
						"GROUP_QUALITY_REVIEWER", "label_schema_id", task.getLabelSchemaId().toString(),
						"label_schema_digest", task.getLabelSchemaDigest())));
	}

}
