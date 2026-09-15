package com.medicationvideo.platform.annotationreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.medicationvideo.platform.assetcapture.*;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.qualitycleaning.*;

class AutomaticActionPreparationTests {

	private final UUID project = UUID.randomUUID();

	private final UUID group = UUID.randomUUID();

	private final UUID clip = UUID.randomUUID();

	private final ProjectAccessService access = mock(ProjectAccessService.class);

	private final ClipAssetReviewQueryService assets = mock(ClipAssetReviewQueryService.class);

	private final QualityReviewQueryService views = mock(QualityReviewQueryService.class);

	private final CaptureGroupViewQueryService groups = mock(CaptureGroupViewQueryService.class);

	private final CaptureGroupQualityQueryService quality = mock(CaptureGroupQualityQueryService.class);

	private final AnnotationLabelSchemaService schemas = mock(AnnotationLabelSchemaService.class);

	private final AnnotationTaskRepository tasks = mock(AnnotationTaskRepository.class);

	private final AnnotationRevisionRepository revisions = mock(AnnotationRevisionRepository.class);

	private final AuditEventRecorder audit = mock(AuditEventRecorder.class);

	private final CaptureGroupAnnotationWorkflow workflow = new CaptureGroupAnnotationWorkflow(access, assets, views,
			groups, quality, null, null, null, schemas, tasks, revisions, null, audit);

	private void ready() {
		var stream = UUID.randomUUID();
		var channel = UUID.randomUUID();
		var snapshot = UUID.randomUUID();
		var view = new CaptureGroupViewQueryService.CaptureGroupView(stream, clip, channel, 1, "view", "view",
				"INGESTED", "PASS", 1, 1, true);
		var stored = new HashMap<String, Object>();
		stored.put("camera_number", 1);
		stored.put("camera_stream_id", stream.toString());
		stored.put("clip_asset_id", clip.toString());
		stored.put("camera_channel_id", channel.toString());
		stored.put("present", true);
		stored.put("media_status", "INGESTED");
		stored.put("technical_quality_status", "PASS");
		stored.put("stream_version", 1);
		stored.put("clip_asset_version", 1);
		stored.put("asset_available", true);
		stored.put("quality_review_decision", "QUALIFIED");
		stored.put("quality_review_version", 1);
		var set = Map.<String, Object>of("schema_version", "capture-group-view-set.v1", "capture_group_id",
				group.toString(), "capture_group_version", 1, "camera_set_snapshot_id", snapshot.toString(),
				"receipt_state", "COMPLETE", "views", List.of(stored));
		when(groups.lockAndGet(project, group)).thenReturn(new CaptureGroupViewQueryService.CaptureGroupFacts(project,
				group, CaptureGroupReceiptState.COMPLETE, 1, snapshot, true, List.of(view)));
		when(views.findCurrentDecisions(eq(project), any()))
			.thenReturn(Map.of(clip, new QualityReviewQueryService.CurrentDecision("QUALIFIED", 1)));
		when(quality.lockAndGetCurrentDecision(project, group))
			.thenReturn(new CaptureGroupQualityQueryService.CurrentDecision(UUID.randomUUID(), project, group,
					"QUALIFIED", "synthetic", "member", 1, set, "input-1", "a".repeat(64), Instant.now()));
		when(assets.lockAndGetReviewFacts(project, clip))
			.thenReturn(new ClipAssetReviewQueryService.ReviewFacts(true, "INGESTED", "a".repeat(64)));
		when(schemas.currentBinding()).thenReturn(new AnnotationLabelSchemaBinding(UUID.randomUUID(), "a".repeat(64)));
	}

	@Test
	void createsTaskAndPrimaryOnceWithoutRevision() {
		ready();
		// Capture the persisted task and replay the same preparation.
		when(tasks.saveAndFlush(any())).thenAnswer(call -> {
			AnnotationTask task = call.getArgument(0);
			when(tasks.findForUpdateByProjectIdAndCaptureGroupId(project, group)).thenReturn(Optional.of(task));
			return task;
		});
		assertTrue(workflow.prepareAutomatic(project, group, "member"));
		assertTrue(workflow.prepareAutomatic(project, group, "member"));
		verify(tasks, times(1)).saveAndFlush(any());
		verify(revisions, never()).saveAndFlush(any());
		verify(audit, times(1)).record(any());
	}

	@Test
	void doesNotPrepareUnreviewedQuality() {
		when(quality.lockAndGetCurrentDecision(project, group))
			.thenReturn(new CaptureGroupQualityQueryService.CurrentDecision(null, project, group, "UNREVIEWED", null,
					null, 0, null, null, null, null));
		assertFalse(workflow.prepareAutomatic(project, group, "member"));
		verifyNoInteractions(tasks, schemas, revisions);
	}

	@Test
	void rejectsStaleInput() {
		ready();
		when(views.findCurrentDecisions(eq(project), any()))
			.thenReturn(Map.of(clip, new QualityReviewQueryService.CurrentDecision("REJECTED", 2)));
		assertFalse(workflow.prepareAutomatic(project, group, "member"));
		verify(tasks, never()).saveAndFlush(any());
	}

	@Test
	void permissionFailureCannotCreateTask() {
		doThrow(new IllegalStateException("membership revoked")).when(access).lockAndRequireMember(project, "member");
		assertThrows(IllegalStateException.class, () -> workflow.prepareAutomatic(project, group, "member"));
		verifyNoInteractions(tasks, quality);
	}

}
