package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.ClipAssetReviewQueryService;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

@Service
class AnnotationTaskService {

	private final ProjectAccessService projectAccessService;
	private final ClipAssetReviewQueryService assetQueryService;
	private final QualityReviewQueryService qualityReviewQueryService;
	private final AnnotationTaskRepository repository;
	private final AuditEventRecorder auditRecorder;

	AnnotationTaskService(
			ProjectAccessService projectAccessService,
			ClipAssetReviewQueryService assetQueryService,
			QualityReviewQueryService qualityReviewQueryService,
			AnnotationTaskRepository repository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.assetQueryService = assetQueryService;
		this.qualityReviewQueryService = qualityReviewQueryService;
		this.repository = repository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	AnnotationTaskListResponse list(UUID projectId, String actorId) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		List<AnnotationTask> tasks = role == ProjectRole.ADMIN
				? this.repository.findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId)
				: this.repository.findAllByProjectIdAndAssigneeActorIdOrderByCreatedAtDescAnnotationTaskIdDesc(
						projectId, actorId);
		return new AnnotationTaskListResponse(tasks.stream().map(AnnotationTaskResponse::from).toList());
	}

	@Transactional
	AnnotationTaskResponse create(
			UUID projectId,
			UUID clipAssetId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			AnnotationTaskCreateRequest request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var replay = this.repository.findByProjectIdAndCreatedByAndIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesCreationRequest(clipAssetId, request.assigneeActorId())) {
				throw new AnnotationTaskException(
						"ANNOTATION_TASK_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different annotation task.",
						409);
			}
			return AnnotationTaskResponse.from(replay.get());
		}

		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, clipAssetId);
		if (!assetFacts.reviewable()) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_NOT_READY",
					"The clip asset must complete media ingest before an annotation task can be created.",
					409);
		}
		if (!this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, clipAssetId).isQualified()) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_QUALITY_NOT_QUALIFIED",
					"Only a human-qualified clip asset can enter annotation.",
					409);
		}
		this.projectAccessService.requireMember(projectId, request.assigneeActorId());
		if (this.repository.existsByProjectIdAndClipAssetId(projectId, clipAssetId)) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_ALREADY_EXISTS",
					"The clip asset already has an annotation task in this project.",
					409);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var task = this.repository.saveAndFlush(AnnotationTask.create(
				projectId,
				clipAssetId,
				request.assigneeActorId(),
				actorId,
				idempotencyKey,
				now));
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"ANNOTATION_TASK_CREATED",
				"AnnotationTask",
				task.getAnnotationTaskId().toString(),
				Long.toString(task.getVersion()),
				AuditOutcome.SUCCEEDED,
				"QUALIFIED_CLIP_ASSET",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"clip_asset_id", clipAssetId.toString(),
						"assignee_actor_id", task.getAssigneeActorId())));
		return AnnotationTaskResponse.from(task);
	}
}

record AnnotationTaskCreateRequest(String assigneeActorId) {
}

record AnnotationTaskResponse(
		UUID annotationTaskId,
		UUID projectId,
		UUID clipAssetId,
		String assigneeActorId,
		String createdBy,
		String status,
		Instant createdAt,
		Instant updatedAt,
		long version) {

	static AnnotationTaskResponse from(AnnotationTask task) {
		return new AnnotationTaskResponse(
				task.getAnnotationTaskId(),
				task.getProjectId(),
				task.getClipAssetId(),
				task.getAssigneeActorId(),
				task.getCreatedBy(),
				task.getStatus().name(),
				task.getCreatedAt(),
				task.getUpdatedAt(),
				task.getVersion());
	}
}

record AnnotationTaskListResponse(List<AnnotationTaskResponse> items) {
}
