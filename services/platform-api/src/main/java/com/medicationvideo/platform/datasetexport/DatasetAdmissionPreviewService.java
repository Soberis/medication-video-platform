package com.medicationvideo.platform.datasetexport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.annotationreview.AnnotationDatasetAdmissionFact;
import com.medicationvideo.platform.annotationreview.AnnotationDatasetAdmissionQueryService;
import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;
import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionQueryService;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

/**
 * Computes a read-only, current-state preview. It does not create a dataset member or override a guard.
 */
@Service
class DatasetAdmissionPreviewService {

    private final CaptureSessionDatasetAdmissionQueryService captureSessionQueryService;
    private final QualityReviewQueryService qualityReviewQueryService;
    private final AnnotationDatasetAdmissionQueryService annotationQueryService;

    DatasetAdmissionPreviewService(
            CaptureSessionDatasetAdmissionQueryService captureSessionQueryService,
            QualityReviewQueryService qualityReviewQueryService,
            AnnotationDatasetAdmissionQueryService annotationQueryService) {
        this.captureSessionQueryService = captureSessionQueryService;
        this.qualityReviewQueryService = qualityReviewQueryService;
        this.annotationQueryService = annotationQueryService;
    }

    @Transactional(readOnly = true)
    DatasetAdmissionPreviewResponse preview(UUID projectId, UUID captureSessionId) {
        return evaluate(projectId, captureSessionId).preview();
    }

    @Transactional(readOnly = true)
    DatasetAdmissionEvaluation evaluate(UUID projectId, UUID captureSessionId) {
        var session = this.captureSessionQueryService.getFacts(projectId, captureSessionId);
        var clipAssetIds = session.streams().stream()
                .map(CaptureSessionDatasetAdmissionFacts.Stream::clipAssetId).toList();
        var qualityByAssetId = this.qualityReviewQueryService.findCurrentDecisions(projectId, clipAssetIds);
        var annotationsByAssetId = this.annotationQueryService.findLatestByClipAssetIds(projectId, clipAssetIds);
        var sessionReasons = sessionReasons(session);
        var streams = session.streams().stream()
                .map(stream -> streamPreview(stream, qualityByAssetId, annotationsByAssetId))
                .toList();
        var eligible = sessionReasons.isEmpty() && streams.stream().allMatch(StreamDatasetAdmissionPreview::eligible);
        var preview = new DatasetAdmissionPreviewResponse(
                session.captureSessionId(),
                eligible,
                session.assemblyStatus(),
                session.qualityStatus(),
                session.expectedCameraCount(),
                session.receivedCameraCount(),
                sessionReasons,
                streams);
        var evidence = session.streams().stream()
                .map(stream -> streamEvidence(stream, qualityByAssetId, annotationsByAssetId))
                .toList();
        return new DatasetAdmissionEvaluation(session, preview, evidence);
    }

    private static List<String> sessionReasons(CaptureSessionDatasetAdmissionFacts session) {
        var reasons = new ArrayList<String>();
        if (!"COMPLETE".equals(session.assemblyStatus())) {
            reasons.add("SESSION_ASSEMBLY_INCOMPLETE");
        }
        if (session.expectedCameraCount() != session.receivedCameraCount()
                || session.expectedCameraCount() != session.streams().size()) {
            reasons.add("SESSION_STREAM_COUNT_MISMATCH");
        }
        if (!"PASS".equals(session.qualityStatus())) {
            reasons.add("SESSION_QUALITY_NOT_PASS");
        }
        return List.copyOf(reasons);
    }

    private static StreamDatasetAdmissionPreview streamPreview(
            CaptureSessionDatasetAdmissionFacts.Stream stream,
            Map<UUID, QualityReviewQueryService.CurrentDecision> qualityByAssetId,
            Map<UUID, AnnotationDatasetAdmissionFact> annotationsByAssetId) {
        var reasons = new ArrayList<String>();
        if (!"INGESTED".equals(stream.streamStatus())) {
            reasons.add("STREAM_NOT_INGESTED");
        }
        if ("REJECT".equals(stream.qualityStatus())) {
            reasons.add("STREAM_QUALITY_REJECTED");
        }
        if (!"INGESTED".equals(stream.assetStatus())) {
            reasons.add("CLIP_ASSET_NOT_INGESTED");
        }
        if (stream.assetDeleted()) {
            reasons.add("CLIP_ASSET_DELETED");
        }
        var quality = qualityByAssetId.get(stream.clipAssetId());
        var qualityDecision = quality == null ? "UNREVIEWED" : quality.decision();
        if (!"QUALIFIED".equals(qualityDecision)) {
            reasons.add("QUALITY_REVIEW_NOT_QUALIFIED");
        }
        var annotation = annotationsByAssetId.get(stream.clipAssetId());
        if (annotation == null) {
            reasons.add("ANNOTATION_REVISION_MISSING");
            return new StreamDatasetAdmissionPreview(
                    stream.cameraStreamId(), stream.clipAssetId(), stream.cameraIndex(), false,
                    stream.streamStatus(), stream.qualityStatus(), stream.assetStatus(), stream.assetDeleted(),
                    qualityDecision, null, "UNREVIEWED", "MISSING", null, null, List.copyOf(reasons));
        }
        if (!"APPROVED".equals(annotation.reviewDecision())) {
            reasons.add("ANNOTATION_REVIEW_NOT_APPROVED");
        }
        var cleaning = annotation.postCleaningResult();
        var cleaningDecision = cleaning == null ? "MISSING" : cleaning.outcome();
        if (!"PASS".equals(cleaningDecision)) {
            reasons.add("POST_CLEANING_" + cleaningDecision);
        }
        if (cleaning != null && quality != null
                && cleaning.qualityReviewVersion() != quality.version()) {
            reasons.add("QUALITY_REVIEW_CHANGED_AFTER_POST_CLEANING");
        }
        return new StreamDatasetAdmissionPreview(
                stream.cameraStreamId(), stream.clipAssetId(), stream.cameraIndex(), reasons.isEmpty(),
                stream.streamStatus(), stream.qualityStatus(), stream.assetStatus(), stream.assetDeleted(),
                qualityDecision, annotation.annotationRevisionId(), annotation.reviewDecision(),
                cleaningDecision, annotation.documentSha256(), annotation.labelSchemaDigest(), List.copyOf(reasons));
    }

    private static DatasetAdmissionStreamEvidence streamEvidence(
            CaptureSessionDatasetAdmissionFacts.Stream stream,
            Map<UUID, QualityReviewQueryService.CurrentDecision> qualityByAssetId,
            Map<UUID, AnnotationDatasetAdmissionFact> annotationsByAssetId) {
        var quality = qualityByAssetId.get(stream.clipAssetId());
        var annotation = annotationsByAssetId.get(stream.clipAssetId());
        var cleaning = annotation == null ? null : annotation.postCleaningResult();
        return new DatasetAdmissionStreamEvidence(
                stream.cameraStreamId(),
                stream.clipAssetId(),
                stream.cameraIndex(),
                stream.assetSha256(),
                quality == null ? "UNREVIEWED" : quality.decision(),
                quality == null ? 0 : quality.version(),
                annotation == null ? null : annotation.annotationRevisionId(),
                annotation == null ? null : annotation.documentSha256(),
                annotation == null ? null : annotation.labelSchemaDigest(),
                cleaning == null ? null : cleaning.cleaningResultId(),
                cleaning == null ? null : cleaning.cleaningRunId(),
                cleaning == null ? null : cleaning.ruleSetDigest(),
                cleaning == null ? "MISSING" : cleaning.outcome());
    }
}

record DatasetAdmissionEvaluation(
        CaptureSessionDatasetAdmissionFacts captureSession,
        DatasetAdmissionPreviewResponse preview,
        List<DatasetAdmissionStreamEvidence> streamEvidence) {
}

record DatasetAdmissionStreamEvidence(
        UUID cameraStreamId,
        UUID clipAssetId,
        int cameraIndex,
        String assetSha256,
        String qualityReviewDecision,
        long qualityReviewVersion,
        UUID annotationRevisionId,
        String annotationDocumentSha256,
        String labelSchemaDigest,
        UUID cleaningResultId,
        UUID cleaningRunId,
        String cleaningRuleSetDigest,
        String postCleaningOutcome) {
}
