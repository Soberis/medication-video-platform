package com.medicationvideo.platform.annotationreview;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.medicationvideo.platform.assetcapture.ClipAssetReviewQueryService;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQualifiedEvent;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

/**
 * Creates the one P0 annotation task as part of the qualifying quality-review
 * transaction. The event is an internal module boundary, not an externally delivered
 * message.
 */
@Component
class QualifiedAnnotationTaskAutoAssignmentListener {

	private final ClipAssetReviewQueryService assetQueryService;

	private final QualityReviewQueryService qualityReviewQueryService;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final AnnotationTaskRepository taskRepository;

	private final AuditEventRecorder auditRecorder;

	QualifiedAnnotationTaskAutoAssignmentListener(ClipAssetReviewQueryService assetQueryService,
			QualityReviewQueryService qualityReviewQueryService, AnnotationLabelSchemaService labelSchemaService,
			AnnotationTaskRepository taskRepository, AuditEventRecorder auditRecorder) {
		this.assetQueryService = assetQueryService;
		this.qualityReviewQueryService = qualityReviewQueryService;
		this.labelSchemaService = labelSchemaService;
		this.taskRepository = taskRepository;
		this.auditRecorder = auditRecorder;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void assign(QualityReviewQualifiedEvent event) {
		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(event.projectId(), event.clipAssetId());
		var quality = this.qualityReviewQueryService.lockAndGetCurrentDecision(event.projectId(), event.clipAssetId());
		if (!assetFacts.reviewable() || !quality.isQualified() || quality.version() != event.qualityReviewVersion()
				|| this.taskRepository.existsByProjectIdAndClipAssetId(event.projectId(), event.clipAssetId())) {
			return;
		}

		var task = this.taskRepository
			.saveAndFlush(AnnotationTask.create(event.projectId(), event.clipAssetId(), event.qualityReviewerActorId(),
					event.qualityReviewerActorId(), "auto-quality-review-" + event.qualityReviewId(),
					this.labelSchemaService.currentBinding(), event.triggeredAt()));
		this.auditRecorder.record(new AuditEventCommand(event.triggeredAt(), AuditActorType.USER,
				event.qualityReviewerActorId(), event.projectId(), "ANNOTATION_TASK_AUTO_ASSIGNED", "AnnotationTask",
				task.getAnnotationTaskId().toString(), Long.toString(task.getVersion()), AuditOutcome.SUCCEEDED,
				"AUTO_AFTER_QUALITY_QUALIFIED", event.requestId(), event.traceId(),
				"PROJECT_MEMBERSHIP_" + event.qualityReviewerRole().name(),
				Map.of("clip_asset_id", event.clipAssetId().toString(), "quality_review_id",
						event.qualityReviewId().toString(), "quality_review_version",
						Long.toString(event.qualityReviewVersion()), "assignee_actor_id", task.getAssigneeActorId(),
						"assignment_mode", "QUALITY_REVIEWER", "label_schema_id", task.getLabelSchemaId().toString(),
						"label_schema_digest", task.getLabelSchemaDigest())));
	}

}
