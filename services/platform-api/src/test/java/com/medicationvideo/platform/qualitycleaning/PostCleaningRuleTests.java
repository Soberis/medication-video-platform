package com.medicationvideo.platform.qualitycleaning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PostCleaningRuleTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000402");

	private static final UUID REVISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000403");

	private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000404");

	private static final UUID LABEL_SCHEMA_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private static final String SHA256 = "a".repeat(64);

	private static final String LABEL_SCHEMA_DIGEST = "305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203";

	private static final Set<String> LABELS = Set.of("prepare_workspace", "retrieve_medicine");

	@Test
	void validFrozenFactsPassAndMissingDurationRemainsAnExplicitWarning() {
		var pass = PostCleaningService.evaluateRules(command(LABEL_SCHEMA_ID, LABEL_SCHEMA_DIGEST,
				List.of(new PostCleaningService.SegmentSnapshot(0, 100, "prepare_workspace"),
						new PostCleaningService.SegmentSnapshot(100, 200, "retrieve_medicine")),
				300_000L));
		assertEquals(CleaningOutcome.PASS, pass.outcome());
		assertTrue(pass.reasonCodes().isEmpty());

		var warning = PostCleaningService.evaluateRules(command(LABEL_SCHEMA_ID, LABEL_SCHEMA_DIGEST,
				List.of(new PostCleaningService.SegmentSnapshot(0, 100, "prepare_workspace")), null));
		assertEquals(CleaningOutcome.WARNING, warning.outcome());
		assertEquals(List.of("VERIFIED_MEDIA_DURATION_UNAVAILABLE"), warning.reasonCodes());
	}

	@Test
	void inconsistentSchemaLabelsRangesAndDurationAreRejectedWithoutDeletingData() {
		var result = PostCleaningService.evaluateRules(command(UUID.randomUUID(), "b".repeat(64),
				List.of(new PostCleaningService.SegmentSnapshot(100, 100, "unknown_step"),
						new PostCleaningService.SegmentSnapshot(90, 250, "prepare_workspace")),
				200_000L));

		assertEquals(CleaningOutcome.REJECT, result.outcome());
		assertTrue(result.reasonCodes().contains("LABEL_SCHEMA_MISMATCH"));
		assertTrue(result.reasonCodes().contains("SEGMENT_TIME_RANGE_INVALID"));
		assertTrue(result.reasonCodes().contains("PRIMARY_STEP_LABEL_NOT_ALLOWED"));
		assertTrue(result.reasonCodes().contains("PRIMARY_STEP_OVERLAP"));
		assertTrue(result.reasonCodes().contains("SEGMENT_EXCEEDS_VERIFIED_MEDIA_DURATION"));
	}

	@Test
	void mismatchedDocumentDigestAndZeroDurationAreRejectReasons() {
		var command = command(LABEL_SCHEMA_ID, LABEL_SCHEMA_DIGEST,
				List.of(new PostCleaningService.SegmentSnapshot(0, 100, "prepare_workspace")), 0L);
		command = new PostCleaningService.EvaluationCommand(command.projectId(), command.clipAssetId(),
				command.assetSha256(), command.qualityReviewVersion(), command.qualityQualified(),
				command.annotationRevisionId(), command.annotationReviewId(), command.approvedReview(),
				command.documentSha256(), "b".repeat(64), command.taskLabelSchemaId(), command.taskLabelSchemaDigest(),
				command.revisionLabelSchemaId(), command.revisionLabelSchemaDigest(), command.allowedLabels(),
				command.segments(), command.mediaReportId(), command.mediaInputDigest(), command.mediaRecipeDigest(),
				command.verifiedDurationMicroseconds(), command.actorId(), command.authorizationBasis());

		var result = PostCleaningService.evaluateRules(command);

		assertEquals(CleaningOutcome.REJECT, result.outcome());
		assertTrue(result.reasonCodes().contains("ANNOTATION_DOCUMENT_DIGEST_MISMATCH"));
		assertTrue(result.reasonCodes().contains("VERIFIED_MEDIA_DURATION_INVALID"));
	}

	private static PostCleaningService.EvaluationCommand command(UUID revisionSchemaId, String revisionSchemaDigest,
			List<PostCleaningService.SegmentSnapshot> segments, Long durationMicroseconds) {
		return new PostCleaningService.EvaluationCommand(PROJECT_ID, CLIP_ASSET_ID, SHA256, 1, true, REVISION_ID,
				REVIEW_ID, true, SHA256, SHA256, LABEL_SCHEMA_ID, LABEL_SCHEMA_DIGEST, revisionSchemaId,
				revisionSchemaDigest, LABELS, segments, durationMicroseconds == null ? null : UUID.randomUUID(),
				durationMicroseconds == null ? null : SHA256, durationMicroseconds == null ? null : "c".repeat(64),
				durationMicroseconds, "synthetic-reviewer", "SYNTHETIC_TEST");
	}

}
