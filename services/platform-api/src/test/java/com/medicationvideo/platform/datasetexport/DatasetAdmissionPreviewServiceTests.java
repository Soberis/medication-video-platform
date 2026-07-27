package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.annotationreview.AnnotationDatasetAdmissionFact;
import com.medicationvideo.platform.annotationreview.AnnotationDatasetAdmissionQueryService;
import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;
import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionQueryService;
import com.medicationvideo.platform.qualitycleaning.PostCleaningResultSummary;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

class DatasetAdmissionPreviewServiceTests {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID STREAM_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Test
    void marksACompleteFullyApprovedSessionAsEligible() {
        var captureFacts = mock(CaptureSessionDatasetAdmissionQueryService.class);
        var qualityFacts = mock(QualityReviewQueryService.class);
        var annotationFacts = mock(AnnotationDatasetAdmissionQueryService.class);
        when(captureFacts.getFacts(PROJECT_ID, SESSION_ID)).thenReturn(completeSession());
        when(qualityFacts.findCurrentDecisions(any(), any()))
                .thenReturn(Map.of(ASSET_ID, new QualityReviewQueryService.CurrentDecision("QUALIFIED", 1)));
        when(annotationFacts.findLatestByClipAssetIds(any(), any()))
                .thenReturn(Map.of(ASSET_ID, approvedAnnotation()));

        var response = new DatasetAdmissionPreviewService(captureFacts, qualityFacts, annotationFacts)
                .preview(PROJECT_ID, SESSION_ID);

        assertTrue(response.eligible());
        assertTrue(response.blockingReasons().isEmpty());
        assertTrue(response.streams().getFirst().eligible());
        assertEquals("PASS", response.streams().getFirst().postCleaningDecision());
    }

    @Test
    void explainsEveryCurrentGuardThatBlocksThePreview() {
        var captureFacts = mock(CaptureSessionDatasetAdmissionQueryService.class);
        var qualityFacts = mock(QualityReviewQueryService.class);
        var annotationFacts = mock(AnnotationDatasetAdmissionQueryService.class);
        when(captureFacts.getFacts(PROJECT_ID, SESSION_ID)).thenReturn(new CaptureSessionDatasetAdmissionFacts(
                SESSION_ID, 2, 1, "ASSEMBLING", "WARNING", List.of(new CaptureSessionDatasetAdmissionFacts.Stream(
                        STREAM_ID, ASSET_ID, 0, "INGEST_FAILED", "REJECT", "INGEST_FAILED", true, "a".repeat(64)))));
        when(qualityFacts.findCurrentDecisions(any(), any())).thenReturn(Map.of());
        when(annotationFacts.findLatestByClipAssetIds(any(), any())).thenReturn(Map.of());

        var response = new DatasetAdmissionPreviewService(captureFacts, qualityFacts, annotationFacts)
                .preview(PROJECT_ID, SESSION_ID);

        assertFalse(response.eligible());
        assertEquals(List.of(
                "SESSION_ASSEMBLY_INCOMPLETE",
                "SESSION_STREAM_COUNT_MISMATCH",
                "SESSION_QUALITY_NOT_PASS"), response.blockingReasons());
        assertEquals(List.of(
                "STREAM_NOT_INGESTED",
                "STREAM_QUALITY_REJECTED",
                "CLIP_ASSET_NOT_INGESTED",
                "CLIP_ASSET_DELETED",
                "QUALITY_REVIEW_NOT_QUALIFIED",
                "ANNOTATION_REVISION_MISSING"), response.streams().getFirst().blockingReasons());
    }

    @Test
    void blocksAStalePostCleaningResultAfterTheQualityReviewVersionChanges() {
        var captureFacts = mock(CaptureSessionDatasetAdmissionQueryService.class);
        var qualityFacts = mock(QualityReviewQueryService.class);
        var annotationFacts = mock(AnnotationDatasetAdmissionQueryService.class);
        when(captureFacts.getFacts(PROJECT_ID, SESSION_ID)).thenReturn(completeSession());
        when(qualityFacts.findCurrentDecisions(any(), any()))
                .thenReturn(Map.of(ASSET_ID, new QualityReviewQueryService.CurrentDecision("QUALIFIED", 2)));
        when(annotationFacts.findLatestByClipAssetIds(any(), any()))
                .thenReturn(Map.of(ASSET_ID, approvedAnnotation()));

        var response = new DatasetAdmissionPreviewService(captureFacts, qualityFacts, annotationFacts)
                .preview(PROJECT_ID, SESSION_ID);

        assertFalse(response.eligible());
        assertFalse(response.streams().getFirst().eligible());
        assertEquals(
                List.of("QUALITY_REVIEW_CHANGED_AFTER_POST_CLEANING"),
                response.streams().getFirst().blockingReasons());
    }

    private static CaptureSessionDatasetAdmissionFacts completeSession() {
        return new CaptureSessionDatasetAdmissionFacts(
                SESSION_ID, 1, 1, "COMPLETE", "PASS", List.of(new CaptureSessionDatasetAdmissionFacts.Stream(
                        STREAM_ID, ASSET_ID, 0, "INGESTED", "PASS", "INGESTED", false, "a".repeat(64))));
    }

    private static AnnotationDatasetAdmissionFact approvedAnnotation() {
        return new AnnotationDatasetAdmissionFact(
                ASSET_ID, REVISION_ID, "APPROVED", "b".repeat(64), "c".repeat(64),
                new PostCleaningResultSummary(
                        UUID.randomUUID(), UUID.randomUUID(), "p0-post-annotation-cleaning-v1", "v1",
                        "d".repeat(64), 1, "PASS", List.of(), List.of("DOCUMENT_DIGEST"), Instant.EPOCH));
    }
}
