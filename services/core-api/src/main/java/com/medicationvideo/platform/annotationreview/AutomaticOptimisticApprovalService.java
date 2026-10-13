package com.medicationvideo.platform.annotationreview;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.medicationvideo.platform.aicandidate.ActionCandidateJobCompletedEvent;
import com.medicationvideo.platform.aicandidate.HandPoseJobCompletedEvent;

/**
 * 原生全自动乐观核准服务 (V1.36 大道至简闭环). 当视频就绪且组内各机位手部检测成功（检测到手部）且 Qwen 动作分割完成（双合格）时，
 * 自动在后台执行乐观核准：生成首份 Revision 并标记为 APPROVED 与 COMPLETED，直接准入 HDF5 导出。
 *
 * 关键规则与边界： 1. 仅对尚未生成过 Revision 的双合格任务执行首次乐观核准； 2.
 * 人工抽检一键【重新编辑】打回草稿（ASSIGNED）后，系统绝不覆写人工工作，等待人工再次提交； 3. 采用“双 Worker 完成即时事件驱动 +
 * 定时补偿扫描”双重闭环保障。
 */
@Component
@ConditionalOnProperty(name = "platform.annotation.auto-approval.enabled", havingValue = "true", matchIfMissing = true)
public class AutomaticOptimisticApprovalService {

	private static final Logger LOG = LoggerFactory.getLogger(AutomaticOptimisticApprovalService.class);

	private final JdbcTemplate jdbcTemplate;

	private final AnnotationTaskService annotationTaskService;

	private final AnnotationTaskRepository taskRepository;

	private final AnnotationTaskViewRepository taskViewRepository;

	private final AnnotationRevisionRepository revisionRepository;

	private final TransactionTemplate transactionTemplate;

	AutomaticOptimisticApprovalService(JdbcTemplate jdbcTemplate, AnnotationTaskService annotationTaskService,
			AnnotationTaskRepository taskRepository, AnnotationTaskViewRepository taskViewRepository,
			AnnotationRevisionRepository revisionRepository, PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.annotationTaskService = annotationTaskService;
		this.taskRepository = taskRepository;
		this.taskViewRepository = taskViewRepository;
		this.revisionRepository = revisionRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onActionCandidateCompleted(ActionCandidateJobCompletedEvent event) {
		adoptCaptureGroupIfEligible(event.projectId(), event.captureGroupId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onHandPoseCompleted(HandPoseJobCompletedEvent event) {
		adoptCaptureGroupIfEligible(event.projectId(), event.captureGroupId());
	}

	public void adoptCaptureGroupIfEligible(UUID projectId, UUID captureGroupId) {
		if (projectId == null || captureGroupId == null) {
			return;
		}
		try {
			if (isEligible(captureGroupId)) {
				this.transactionTemplate.executeWithoutResult(status -> adoptCaptureGroup(projectId, captureGroupId));
			}
		}
		catch (Exception ex) {
			LOG.warn("Event-driven optimistic approval deferred for group {}: {}", captureGroupId, ex.getMessage());
		}
	}

	@Scheduled(fixedDelayString = "${platform.annotation.auto-approval.poll-delay:PT5S}")
	public void reconcile() {
		var sql = """
				SELECT cs.capture_session_id, cs.project_id
				FROM capture_session cs
				LEFT JOIN annotation_task t ON cs.capture_session_id = t.capture_group_id
				WHERE cs.receipt_state = 'COMPLETE'
				  AND (t.annotation_task_id IS NULL
				       OR (t.task_status != 'COMPLETED'
				           AND NOT EXISTS (
				               SELECT 1 FROM annotation_revision ar
				               WHERE ar.annotation_task_id = t.annotation_task_id
				           )
				       )
				  )
				  AND EXISTS (
				    SELECT 1 FROM camera_stream s WHERE s.capture_session_id = cs.capture_session_id
				  )
				  AND NOT EXISTS (
				    SELECT 1 FROM camera_stream s
				    WHERE s.capture_session_id = cs.capture_session_id
				      AND (
				        NOT EXISTS (
				          SELECT 1 FROM ai_job hj
				          WHERE hj.clip_asset_id = s.clip_asset_id
				            AND hj.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
				            AND hj.hand_result = 'HAND_DETECTED'
				            AND hj.status = 'SUCCEEDED'
				        )
				        OR NOT EXISTS (
				          SELECT 1 FROM candidate_annotation ca
				          WHERE ca.clip_asset_id = s.clip_asset_id
				        )
				        OR NOT EXISTS (
				          SELECT 1 FROM ai_job aj
				          JOIN action_candidate ac ON aj.ai_job_id = ac.ai_job_id
				          WHERE aj.clip_asset_id = s.clip_asset_id
				            AND aj.algorithm_key = 'QWEN3_5_ACTION'
				            AND aj.status = 'SUCCEEDED'
				        )
				      )
				  )
				ORDER BY cs.created_at ASC
				LIMIT 10;
				""";

		List<CaptureGroupTarget> targets = this.jdbcTemplate.query(sql,
				(rs, rowNum) -> new CaptureGroupTarget((UUID) rs.getObject("capture_session_id"),
						(UUID) rs.getObject("project_id")));

		for (var target : targets) {
			try {
				this.transactionTemplate
					.executeWithoutResult(status -> adoptCaptureGroup(target.projectId(), target.captureGroupId()));
			}
			catch (Exception ex) {
				LOG.warn("Automatic optimistic approval deferred for group {}: {}", target.captureGroupId(),
						ex.getMessage());
			}
		}
	}

	private boolean isEligible(UUID captureGroupId) {
		var sql = """
				SELECT 1
				FROM capture_session cs
				LEFT JOIN annotation_task t ON cs.capture_session_id = t.capture_group_id
				WHERE cs.capture_session_id = ?
				  AND cs.receipt_state = 'COMPLETE'
				  AND (t.annotation_task_id IS NULL
				       OR (t.task_status != 'COMPLETED'
				           AND NOT EXISTS (
				               SELECT 1 FROM annotation_revision ar
				               WHERE ar.annotation_task_id = t.annotation_task_id
				           )
				       )
				  )
				  AND EXISTS (
				    SELECT 1 FROM camera_stream s WHERE s.capture_session_id = cs.capture_session_id
				  )
				  AND NOT EXISTS (
				    SELECT 1 FROM camera_stream s
				    WHERE s.capture_session_id = cs.capture_session_id
				      AND (
				        NOT EXISTS (
				          SELECT 1 FROM ai_job hj
				          WHERE hj.clip_asset_id = s.clip_asset_id
				            AND hj.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
				            AND hj.hand_result = 'HAND_DETECTED'
				            AND hj.status = 'SUCCEEDED'
				        )
				        OR NOT EXISTS (
				          SELECT 1 FROM candidate_annotation ca
				          WHERE ca.clip_asset_id = s.clip_asset_id
				        )
				        OR NOT EXISTS (
				          SELECT 1 FROM ai_job aj
				          JOIN action_candidate ac ON aj.ai_job_id = ac.ai_job_id
				          WHERE aj.clip_asset_id = s.clip_asset_id
				            AND aj.algorithm_key = 'QWEN3_5_ACTION'
				            AND aj.status = 'SUCCEEDED'
				        )
				      )
				  )
				LIMIT 1;
				""";
		var rows = this.jdbcTemplate.queryForList(sql, Integer.class, captureGroupId);
		return !rows.isEmpty();
	}

	private void adoptCaptureGroup(UUID projectId, UUID captureGroupId) {
		String adminActor = resolveAdminActor(projectId);
		var originalAuth = SecurityContextHolder.getContext().getAuthentication();
		var systemAdminAuth = new UsernamePasswordAuthenticationToken(adminActor, null,
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
		SecurityContextHolder.getContext().setAuthentication(systemAdminAuth);
		try {
			String traceId = "auto-approval";

			// 1. 创建或准备 AnnotationTask
			var taskResponse = this.annotationTaskService.assignForHumanLabel(projectId, captureGroupId, adminActor,
					adminActor, "auto-adopt-" + captureGroupId, UUID.randomUUID().toString(), traceId);

			UUID taskId = taskResponse.annotationTaskId();
			if ("COMPLETED".equals(taskResponse.status())) {
				return;
			}

			// 防御性检查：若已有任何 Revision（说明已被人工打回或处理），切勿覆写
			if (this.revisionRepository.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(taskId).isPresent()) {
				return;
			}

			// 2. 为每个机位更新机位视图并提交
			String inputVersion = taskResponse.groupInputVersion();
			var views = taskResponse.cameraViews();
			if (views == null || views.isEmpty()) {
				views = this.taskViewRepository.findAllByAnnotationTaskIdOrderByCameraNumberAsc(taskId)
					.stream()
					.map(AnnotationTaskCameraViewResponse::from)
					.toList();
			}

			for (var view : views) {
				int cameraNumber = view.cameraNumber();
				UUID clipAssetId = view.clipAssetId();
				if (clipAssetId == null) {
					continue;
				}
				if (view.state() == AnnotationTaskViewState.SUBMITTED && !view.segments().isEmpty()) {
					continue;
				}

				// 查询动作候选
				var candidates = this.jdbcTemplate.query("""
						SELECT c.action_candidate_id, c.start_ms, c.end_ms, c.action_label
						FROM action_candidate c
						WHERE c.project_id = ? AND c.clip_asset_id = ?
						  AND c.ai_job_id = (SELECT j.ai_job_id FROM ai_job j
						                      WHERE j.project_id = c.project_id AND j.clip_asset_id = c.clip_asset_id
						                        AND j.algorithm_key = 'QWEN3_5_ACTION'
						                      ORDER BY j.created_at DESC, j.ai_job_id DESC LIMIT 1)
						  AND EXISTS (SELECT 1 FROM ai_job j WHERE j.ai_job_id = c.ai_job_id AND j.status = 'SUCCEEDED')
						ORDER BY c.start_ms ASC, c.end_ms ASC
						""",
						(rs, rowNum) -> new CandidateRecord((UUID) rs.getObject("action_candidate_id"),
								rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("action_label")),
						projectId, clipAssetId);

				if (candidates.isEmpty()) {
					LOG.warn("Group {} camera {} has no action candidates, skipping auto-adopt", captureGroupId,
							cameraNumber);
					return;
				}

				var durationRows = this.jdbcTemplate.queryForList(
						"SELECT duration_microseconds FROM media_report WHERE clip_asset_id = ? LIMIT 1", Long.class,
						clipAssetId);
				Long durationMs = (!durationRows.isEmpty() && durationRows.getFirst() != null)
						? durationRows.getFirst() / 1000 : null;

				List<AnnotationCameraViewSegmentCommand> segments = new ArrayList<>();
				for (var cand : candidates) {
					long startMs = Math.max(0, cand.startMs());
					long endMs = durationMs != null ? Math.min(cand.endMs(), durationMs) : cand.endMs();
					if (endMs > startMs) {
						segments.add(new AnnotationCameraViewSegmentCommand(UUID.randomUUID(), startMs, endMs,
								cand.label(), List.of(cand.candidateId())));
					}
				}

				if (segments.isEmpty()) {
					LOG.warn("Group {} camera {} has no valid bounded segments, skipping auto-adopt", captureGroupId,
							cameraNumber);
					return;
				}

				var currentView = this.taskViewRepository.findForUpdate(projectId, taskId, cameraNumber);
				long expectedVersion = currentView.map(AnnotationTaskView::getVersion).orElse(view.version());

				var updateCmd = new AnnotationCameraViewUpdateCommand(expectedVersion, inputVersion,
						AnnotationTaskViewState.SUBMITTED, segments, "AI 自动采纳", null);

				this.annotationTaskService.updateCameraView(projectId, taskId, cameraNumber, adminActor, true,
						"auto-view-" + taskId + "-" + cameraNumber, UUID.randomUUID().toString(), traceId, updateCmd);
			}

			// 3. 提交整组首份标注修订 (Revision 1)
			var currentTask = this.taskRepository.findForUpdate(projectId, taskId).orElseThrow();
			if (currentTask.getStatus() == AnnotationTaskStatus.ASSIGNED) {
				var currentViews = this.taskViewRepository.findAllByAnnotationTaskIdOrderByCameraNumberAsc(taskId)
					.stream()
					.map(AnnotationTaskCameraViewResponse::from)
					.toList();
				List<AnnotationCorrespondenceCommand> correspondences = new ArrayList<>();
				for (var v : currentViews) {
					for (var seg : v.segments()) {
						UUID segId = parseUuid(seg.get("segment_id"));
						if (segId != null) {
							correspondences.add(new AnnotationCorrespondenceCommand(UUID.randomUUID(),
									AnnotationCorrespondenceStatus.UNCERTAIN,
									List.of(new AnnotationCorrespondenceSegmentRefCommand(v.cameraNumber(), segId)),
									"单机位自动采纳"));
						}
					}
				}

				if (!correspondences.isEmpty()) {
					var submitCmd = new AnnotationRevisionSubmitCommand(currentTask.getVersion(),
							currentTask.getGroupInputVersion(), correspondences, "AI 自动采纳");

					var revResponse = this.annotationTaskService.submitRevision(projectId, taskId, adminActor,
							"auto-rev-" + taskId, UUID.randomUUID().toString(), traceId, submitCmd);

					// 4. 乐观核准通过 (标记 APPROVED 与 COMPLETED)
					approveRevision(projectId, revResponse.annotationRevisionId(), taskId, adminActor, traceId);
					LOG.info("Capture group {} automatically approved (Revision {})", captureGroupId,
							revResponse.revisionNumber());
				}
			}
		}
		finally {
			SecurityContextHolder.getContext().setAuthentication(originalAuth);
		}
	}

	private void approveRevision(UUID projectId, UUID revisionId, UUID taskId, String adminActor, String traceId) {
		var views = this.taskViewRepository.findAllByAnnotationTaskIdOrderByCameraNumberAsc(taskId);
		List<Integer> cameraNumbers = views.stream().map(AnnotationTaskView::getCameraNumber).toList();
		var reviewCmd = new AnnotationReviewCommand(AnnotationReviewDecision.APPROVED, "AI 自动核验通过", cameraNumbers,
				List.of());
		this.annotationTaskService.reviewRevision(projectId, revisionId, adminActor, "auto-review-" + revisionId,
				UUID.randomUUID().toString(), traceId, reviewCmd);
	}

	private String resolveAdminActor(UUID projectId) {
		var rows = this.jdbcTemplate.queryForList(
				"SELECT actor_id FROM membership WHERE project_id = ? AND role = 'ADMIN' AND status = 'ACTIVE' ORDER BY created_at ASC LIMIT 1",
				String.class, projectId);
		if (!rows.isEmpty() && rows.getFirst() != null) {
			return rows.getFirst();
		}
		return "admin";
	}

	private static UUID parseUuid(Object val) {
		if (val instanceof UUID u) {
			return u;
		}
		if (val instanceof String s && !s.isBlank()) {
			try {
				return UUID.fromString(s);
			}
			catch (Exception ignored) {
			}
		}
		return null;
	}

	private record CaptureGroupTarget(UUID captureGroupId, UUID projectId) {
	}

	private record CandidateRecord(UUID candidateId, long startMs, long endMs, String label) {
	}

}
