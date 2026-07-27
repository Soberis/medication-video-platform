package com.medicationvideo.platform.annotationreview;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.qualitycleaning.PostCleaningQueryService;

/**
 * Read-only annotation facts exposed to the later dataset module.
 */
@Service
public class AnnotationDatasetAdmissionQueryService {

    private final AnnotationTaskRepository taskRepository;
    private final AnnotationRevisionRepository revisionRepository;
    private final AnnotationReviewRepository reviewRepository;
    private final PostCleaningQueryService postCleaningQueryService;

    AnnotationDatasetAdmissionQueryService(
            AnnotationTaskRepository taskRepository,
            AnnotationRevisionRepository revisionRepository,
            AnnotationReviewRepository reviewRepository,
            PostCleaningQueryService postCleaningQueryService) {
        this.taskRepository = taskRepository;
        this.revisionRepository = revisionRepository;
        this.reviewRepository = reviewRepository;
        this.postCleaningQueryService = postCleaningQueryService;
    }

    @Transactional(readOnly = true)
    public Map<UUID, AnnotationDatasetAdmissionFact> findLatestByClipAssetIds(
            UUID projectId, Collection<UUID> clipAssetIds) {
        if (clipAssetIds.isEmpty()) {
            return Map.of();
        }
        var tasks = this.taskRepository.findAllByProjectIdAndClipAssetIdIn(projectId, List.copyOf(clipAssetIds));
        if (tasks.isEmpty()) {
            return Map.of();
        }
        var latestRevisionsByTaskId = this.revisionRepository.findAllByAnnotationTaskIdIn(
                        tasks.stream().map(AnnotationTask::getAnnotationTaskId).toList())
                .stream()
                .collect(Collectors.toMap(
                        AnnotationRevision::getAnnotationTaskId,
                        Function.identity(),
                        java.util.function.BinaryOperator.maxBy(Comparator.comparingInt(AnnotationRevision::getRevisionNumber))));
        if (latestRevisionsByTaskId.isEmpty()) {
            return Map.of();
        }
        var reviewsByRevisionId = this.reviewRepository.findAllByAnnotationRevisionIdIn(
                        latestRevisionsByTaskId.values().stream()
                                .map(AnnotationRevision::getAnnotationRevisionId).toList())
                .stream()
                .collect(Collectors.toMap(AnnotationReview::getAnnotationRevisionId, Function.identity()));
        var cleaningByRevisionId = this.postCleaningQueryService.findLatestByAnnotationRevisionIds(
                latestRevisionsByTaskId.values().stream()
                        .map(AnnotationRevision::getAnnotationRevisionId).toList());
        return tasks.stream()
                .map(task -> toFact(task, latestRevisionsByTaskId.get(task.getAnnotationTaskId()),
                        reviewsByRevisionId, cleaningByRevisionId))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(AnnotationDatasetAdmissionFact::clipAssetId, Function.identity()));
    }

    private static AnnotationDatasetAdmissionFact toFact(
            AnnotationTask task,
            AnnotationRevision revision,
            Map<UUID, AnnotationReview> reviewsByRevisionId,
            Map<UUID, com.medicationvideo.platform.qualitycleaning.PostCleaningResultSummary> cleaningByRevisionId) {
        if (revision == null) {
            return null;
        }
        var review = reviewsByRevisionId.get(revision.getAnnotationRevisionId());
        return new AnnotationDatasetAdmissionFact(
                task.getClipAssetId(),
                revision.getAnnotationRevisionId(),
                review == null ? "UNREVIEWED" : review.getDecision().name(),
                revision.getDocumentSha256(),
                revision.getLabelSchemaDigest(),
                cleaningByRevisionId.get(revision.getAnnotationRevisionId()));
    }
}
